package com.github.pagehelper.helper;

import com.github.pagehelper.PageException;
import com.github.pagehelper.helper.dialect.*;
import com.github.pagehelper.util.StringUtil;
import org.apache.ibatis.mapping.MappedStatement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基础方言信息
 *
 * @author liuzh
 */
public abstract class BaseAutoDialect extends BaseParams {

    private static Map<String, Class<?>> dialectAliasMap = new HashMap<String, Class<?>>();

    static {
        //注册别名
        dialectAliasMap.put("hsqldb", HsqldbDialect.class);
        dialectAliasMap.put("h2", HsqldbDialect.class);
        dialectAliasMap.put("postgreSQL", HsqldbDialect.class);

        dialectAliasMap.put("mysql", MySqlDialect.class);
        dialectAliasMap.put("mariadb", MySqlDialect.class);
        dialectAliasMap.put("sqlite", MySqlDialect.class);

        dialectAliasMap.put("oracle", OracleDialect.class);
        dialectAliasMap.put("db2", Db2Dialect.class);
        dialectAliasMap.put("informix", InformixDialect.class);

        dialectAliasMap.put("sqlserver", SqlServerDialect.class);
        dialectAliasMap.put("sqlserver2012", SqlServer2012Dialect.class);
    }

    //自动获取dialect,如果没有setProperties或setSqlUtilConfig，也可以正常进行
    private boolean autoDialect = true;
    //多数据源时，获取jdbcurl后是否关闭数据源
    private boolean closeConn = true;
    //属性配置
    private Properties properties;
    //缓存
    private Map<String, HelperDialect> urlDialectMap = new ConcurrentHashMap<String, HelperDialect>();
    private ReentrantLock lock = new ReentrantLock();
    private HelperDialect delegate;
    private ThreadLocal<HelperDialect> dialectThreadLocal = new ThreadLocal<HelperDialect>();

    //多数据动态获取时，每次需要初始化
    protected void initDelegate(MappedStatement ms) {
        if (delegate == null) {
            if (autoDialect) {
                this.delegate = getDialect(ms);
            } else {
                dialectThreadLocal.set(getDialect(ms));
            }
        }
    }

    //获取当前的代理对象
    protected HelperDialect getDelegate() {
        if (delegate != null) {
            return delegate;
        }
        return dialectThreadLocal.get();
    }

    //移除代理对象
    protected void clearDelegate() {
        dialectThreadLocal.remove();
    }

    private String fromJdbcUrl(String jdbcUrl) {
        for (String dialect : dialectAliasMap.keySet()) {
            if (jdbcUrl.indexOf(":" + dialect + ":") != -1) {
                return dialect;
            }
        }
        return null;
    }

    /**
     * 反射类
     *
     * @param className
     * @return
     * @throws Exception
     */
    private Class resloveDialectClass(String className) throws Exception {
        if (dialectAliasMap.containsKey(className.toLowerCase())) {
            return dialectAliasMap.get(className.toLowerCase());
        } else {
            return Class.forName(className);
        }
    }

    /**
     * 初始化 helper
     *
     * @param dialectClass
     * @param properties
     */
    private HelperDialect initDialect(String dialectClass, Properties properties) {
        HelperDialect dialect;
        if (StringUtil.isEmpty(dialectClass)) {
            throw new RuntimeException("使用 PageHelper 分页插件时，必须设置 helper 属性");
        }
        try {
            Class sqlDialectClass = resloveDialectClass(dialectClass);
            if (HelperDialect.class.isAssignableFrom(sqlDialectClass)) {
                dialect = (HelperDialect) sqlDialectClass.newInstance();
            } else {
                throw new PageException("使用 PageHelper 时，方言必须是实现 " + HelperDialect.class.getCanonicalName() + " 接口的实现类!");
            }
        } catch (Exception e) {
            throw new PageException("初始化 helper [" + dialectClass + "]时出错:" + e.getMessage(), e);
        }
        dialect.setProperties(properties);
        return dialect;
    }

    /**
     * 获取url
     *
     * @param dataSource
     * @return
     */
    private String getUrl(DataSource dataSource) {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            return conn.getMetaData().getURL();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    if (closeConn) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    //ignore
                }
            }
        }
    }

    /**
     * 根据 jdbcUrl 获取数据库方言
     *
     * @param ms
     * @return
     */
    private HelperDialect getDialect(MappedStatement ms) {
        //改为对dataSource做缓存
        DataSource dataSource = ms.getConfiguration().getEnvironment().getDataSource();
        String url = getUrl(dataSource);
        if (urlDialectMap.containsKey(url)) {
            return urlDialectMap.get(url);
        }
        try {
            lock.lock();
            if (urlDialectMap.containsKey(url)) {
                return urlDialectMap.get(url);
            }
            if (StringUtil.isEmpty(url)) {
                throw new RuntimeException("无法自动获取jdbcUrl，请在分页插件中配置dialect参数!");
            }
            String dialectStr = fromJdbcUrl(url);
            if (dialectStr == null) {
                throw new RuntimeException("无法自动获取数据库类型，请通过 helper 参数指定!");
            }
            HelperDialect dialect = initDialect(dialectStr, properties);
            urlDialectMap.put(url, dialect);
            return dialect;
        } finally {
            lock.unlock();
        }
    }

    public void setProperties(Properties properties) {
        super.setProperties(properties);
        //多数据源时，获取 jdbcurl 后是否关闭数据源
        String closeConn = properties.getProperty("closeConn");
        if (StringUtil.isNotEmpty(closeConn)) {
            this.closeConn = Boolean.parseBoolean(closeConn);
        }
        //指定的 Helper 数据库方言，和 dialect 不同
        String dialect = properties.getProperty("helperDialect");
        //运行时获取数据源
        String runtimeDialect = properties.getProperty("autoRuntimeDialect");
        //1.动态多数据源
        if (StringUtil.isNotEmpty(runtimeDialect) && runtimeDialect.equalsIgnoreCase("TRUE")) {
            this.autoDialect = false;
            this.properties = properties;
        }
        //2.动态获取方言
        else if (StringUtil.isEmpty(dialect)) {
            autoDialect = true;
            this.properties = properties;
        }
        //3.指定方言
        else {
            autoDialect = false;
            this.delegate = initDialect(dialect, properties);
        }
    }
}
