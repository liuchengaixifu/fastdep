package com.louislivi.fastdep.datasource;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyNameAliases;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;

import javax.sql.DataSource;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * FastDepDataSource Register
 *
 * @author : louislivi
 */
@SuppressWarnings("unchecked")
public class FastDepDataSourceRegister implements EnvironmentAware, ImportBeanDefinitionRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(FastDepDataSourceRegister.class);
    private final static ConfigurationPropertyNameAliases ALIASES = new ConfigurationPropertyNameAliases();

    private static Map<String, Object> registerBean = new ConcurrentHashMap<>();

    static {
        ALIASES.addAliases("url", "jdbc-url");
        ALIASES.addAliases("username", "user");
    }

    private Environment env;
    private Binder binder;

    /**
     * ImportBeanDefinitionRegistrar
     *
     * @param annotationMetadata     annotationMetadata
     * @param beanDefinitionRegistry beanDefinitionRegistry
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry beanDefinitionRegistry) {
        // get all datasource
        Map<String, Map> multipleDataSources;
        try {
            multipleDataSources = binder.bind("fastdep.datasource", Map.class).get();
        } catch (NoSuchElementException e) {
            logger.error("Failed to configure fastDep DataSource: 'fastdep.datasource' attribute is not specified and no embedded datasource could be configured.");
            return;
        }
        for (String key : multipleDataSources.keySet()) {
            // datasource
            Supplier<DataSource> dataSourceSupplier = () -> {
                //获取注册数据
                AtomikosDataSourceBean registerDataSource = (AtomikosDataSourceBean) registerBean.get(key + "DataSource");
                if (registerDataSource != null) {
                    return registerDataSource;
                }
                registerDataSource = new AtomikosDataSourceBean();
                FastDepDataSourceProperties.DataSource fastDepDataSource = binder.bind("fastdep.datasource." + key, FastDepDataSourceProperties.DataSource.class).get();
                registerDataSource.setXaDataSourceClassName("com.alibaba.druid.pool.xa.DruidXADataSource");
                registerDataSource.setUniqueResourceName(key);
                registerDataSource.setMinPoolSize(fastDepDataSource.getMinIdle());
                registerDataSource.setMaxPoolSize(fastDepDataSource.getMaxActive());
                registerDataSource.setBorrowConnectionTimeout((int) fastDepDataSource.getTimeBetweenEvictionRunsMillis());
                registerDataSource.setMaxIdleTime((int) fastDepDataSource.getMaxEvictableIdleTimeMillis());
                registerDataSource.setTestQuery(fastDepDataSource.getValidationQuery());
                registerDataSource.setXaDataSource(fastDepDataSource);
                registerBean.put(key + "DataSource", registerDataSource);
                return registerDataSource;
            };
            DataSource dataSource = dataSourceSupplier.get();
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(DataSource.class, dataSourceSupplier);
            AbstractBeanDefinition datasourceBean = builder.getRawBeanDefinition();
            datasourceBean.setDependsOn("txManager");
            beanDefinitionRegistry.registerBeanDefinition(key + "DataSource", datasourceBean);
            // sqlSessionFactory
            Supplier<SqlSessionFactory> sqlSessionFactorySupplier = () -> {
                SqlSessionFactory registerSqlSessionFactory = (SqlSessionFactory) registerBean.get(key + "SqlSessionFactory");
                if (registerSqlSessionFactory != null) {
                    return registerSqlSessionFactory;
                }
                try {
                    SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
                    fb.setDataSource(dataSource);
                    fb.setTypeAliasesPackage(env.getProperty("mybatis.typeAliasesPackage"));
                    // mybatis.mapper-locations
                    fb.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(env.getProperty("mybatis.mapper-locations")));
                    // mybatis.configuration
                    BindResult<Configuration> bindConfiguration = binder.bind("mybatis.configuration", Configuration.class);
                    if (bindConfiguration.isBound()) {
                        fb.setConfiguration(bindConfiguration.get());
                    }
                    registerSqlSessionFactory = fb.getObject();
                    registerBean.put(key + "SqlSessionFactory", registerSqlSessionFactory);
                    return registerSqlSessionFactory;
                } catch (Exception e) {
                    logger.error("", e);
                }
                return null;
            };
            SqlSessionFactory sqlSessionFactory = sqlSessionFactorySupplier.get();
            BeanDefinitionBuilder builder2 = BeanDefinitionBuilder.genericBeanDefinition(SqlSessionFactory.class, sqlSessionFactorySupplier);
            BeanDefinition sqlSessionFactoryBean = builder2.getRawBeanDefinition();
            beanDefinitionRegistry.registerBeanDefinition(key + "SqlSessionFactory", sqlSessionFactoryBean);
            // sqlSessionTemplate
            GenericBeanDefinition sqlSessionTemplate = new GenericBeanDefinition();
            sqlSessionTemplate.setBeanClass(SqlSessionTemplate.class);
            ConstructorArgumentValues constructorArgumentValues = new ConstructorArgumentValues();
            constructorArgumentValues.addIndexedArgumentValue(0, sqlSessionFactory);
            sqlSessionTemplate.setConstructorArgumentValues(constructorArgumentValues);
            beanDefinitionRegistry.registerBeanDefinition(key + "SqlSessionTemplate", sqlSessionTemplate);
            // MapperScanner
            ClassPathMapperScanner scanner = new ClassPathMapperScanner(beanDefinitionRegistry);
            scanner.setSqlSessionTemplateBeanName(key + "SqlSessionTemplate");
            scanner.registerFilters();
            String mapperProperty = env.getProperty("fastdep.datasource." + key + ".mapper");
            if (mapperProperty == null) {
                logger.error("Failed to configure fastDep DataSource: fastdep.datasource." + key + ".mapper cannot be null.");
                return;
            }
            scanner.doScan(mapperProperty);
            logger.info("Registration dataSource ({}DataSource) !", key);
        }
        logger.info("Registration dataSource completed !");
    }


    /**
     * init environment
     *
     * @param environment environment
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
        // bing binder
        binder = Binder.get(this.env);
    }

}
