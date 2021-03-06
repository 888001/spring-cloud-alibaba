package org.springframework.cloud.alibaba.sentinel.custom;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.cloud.alibaba.sentinel.SentinelConstants;
import org.springframework.cloud.alibaba.sentinel.SentinelProperties;
import org.springframework.cloud.alibaba.sentinel.datasource.SentinelDataSourceConstants;
import org.springframework.cloud.alibaba.sentinel.datasource.config.AbstractDataSourceProperties;
import org.springframework.cloud.alibaba.sentinel.datasource.config.DataSourcePropertiesConfiguration;
import org.springframework.cloud.alibaba.sentinel.datasource.config.NacosDataSourceProperties;
import org.springframework.cloud.alibaba.sentinel.datasource.converter.JsonConverter;
import org.springframework.cloud.alibaba.sentinel.datasource.converter.XmlConverter;
import org.springframework.context.event.EventListener;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import com.alibaba.csp.sentinel.datasource.AbstractDataSource;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;

/**
 * Sentinel {@link ReadableDataSource} Handler Handle the configurations of
 * 'spring.cloud.sentinel.datasource'
 *
 * @author <a href="mailto:fangjian0423@gmail.com">Jim</a>
 * @see SentinelProperties#datasource
 * @see JsonConverter
 * @see XmlConverter
 */
public class SentinelDataSourceHandler {

	private static final Logger logger = LoggerFactory
			.getLogger(SentinelDataSourceHandler.class);

	private List<String> dataTypeList = Arrays.asList("json", "xml");

	private List<Class> rulesList = Arrays.asList(FlowRule.class, DegradeRule.class,
			SystemRule.class, AuthorityRule.class, ParamFlowRule.class);

	private List<String> dataSourceBeanNameList = Collections
			.synchronizedList(new ArrayList<>());

	private final String DATATYPE_FIELD = "dataType";
	private final String CUSTOM_DATATYPE = "custom";
	private final String CONVERTERCLASS_FIELD = "converterClass";

	@Autowired
	private SentinelProperties sentinelProperties;

	@EventListener(classes = ApplicationStartedEvent.class)
	public void buildDataSource(ApplicationStartedEvent event) throws Exception {

		DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) event
				.getApplicationContext().getAutowireCapableBeanFactory();

		// commercialization
		if (!StringUtils.isEmpty(System.getProperties()
				.getProperty(SentinelDataSourceConstants.NACOS_DATASOURCE_ENDPOINT))) {
			Map<String, DataSourcePropertiesConfiguration> newDataSourceMap = new TreeMap<>(
					String.CASE_INSENSITIVE_ORDER);
			for (Map.Entry<String, DataSourcePropertiesConfiguration> entry : sentinelProperties
					.getDatasource().entrySet()) {
				if (entry.getValue().getValidDataSourceProperties()
						.getClass() != NacosDataSourceProperties.class) {
					newDataSourceMap.put(entry.getKey(), entry.getValue());
				}
			}
			newDataSourceMap.put(SentinelConstants.FLOW_DATASOURCE_NAME,
					new DataSourcePropertiesConfiguration(
							NacosDataSourceProperties.buildFlowByEDAS()));
			newDataSourceMap.put(SentinelConstants.DEGRADE_DATASOURCE_NAME,
					new DataSourcePropertiesConfiguration(
							NacosDataSourceProperties.buildDegradeByEDAS()));
			sentinelProperties.setDatasource(newDataSourceMap);
		}

		sentinelProperties.getDatasource()
				.forEach((dataSourceName, dataSourceProperties) -> {
					List<String> validFields = dataSourceProperties.getValidField();
					if (validFields.size() != 1) {
						logger.error("[Sentinel Starter] DataSource " + dataSourceName
								+ " multi datasource active and won't loaded: "
								+ dataSourceProperties.getValidField());
						return;
					}

					AbstractDataSourceProperties abstractDataSourceProperties = dataSourceProperties
							.getValidDataSourceProperties();
					abstractDataSourceProperties.preCheck();
					registerBean(beanFactory, abstractDataSourceProperties, dataSourceName
							+ "-sentinel-" + validFields.get(0) + "-datasource");
				});

		dataSourceBeanNameList.forEach(beanName -> {
			ReadableDataSource dataSource = beanFactory.getBean(beanName,
					ReadableDataSource.class);
			Object ruleConfig;
			try {
				logger.info("[Sentinel Starter] DataSource " + beanName
						+ " start to loadConfig");
				ruleConfig = dataSource.loadConfig();
			}
			catch (Exception e) {
				logger.error("[Sentinel Starter] DataSource " + beanName
						+ " loadConfig error: " + e.getMessage(), e);
				return;
			}
			SentinelProperty sentinelProperty = dataSource.getProperty();
			Class ruleType = getAndCheckRuleType(ruleConfig, beanName);
			if (ruleType != null) {
				if (ruleType == FlowRule.class) {
					FlowRuleManager.register2Property(sentinelProperty);
				}
				else if (ruleType == DegradeRule.class) {
					DegradeRuleManager.register2Property(sentinelProperty);
				}
				else if (ruleType == SystemRule.class) {
					SystemRuleManager.register2Property(sentinelProperty);
				}
				else if (ruleType == AuthorityRule.class) {
					AuthorityRuleManager.register2Property(sentinelProperty);
				}
				else {
					ParamFlowRuleManager.register2Property(sentinelProperty);
				}
			}
		});
	}

	private void registerBean(DefaultListableBeanFactory beanFactory,
			final AbstractDataSourceProperties dataSourceProperties,
			String dataSourceName) {

		Map<String, Object> propertyMap = Arrays
				.stream(dataSourceProperties.getClass().getDeclaredFields())
				.collect(HashMap::new, (m, v) -> {
					try {
						v.setAccessible(true);
						m.put(v.getName(), v.get(dataSourceProperties));
					}
					catch (IllegalAccessException e) {
						logger.error("[Sentinel Starter] DataSource " + dataSourceName
								+ " field: " + v.getName() + " invoke error");
						throw new RuntimeException(
								"[Sentinel Starter] DataSource " + dataSourceName
										+ " field: " + v.getName() + " invoke error",
								e);
					}
				}, HashMap::putAll);
		propertyMap.put(CONVERTERCLASS_FIELD, dataSourceProperties.getConverterClass());
		propertyMap.put(DATATYPE_FIELD, dataSourceProperties.getDataType());

		BeanDefinitionBuilder builder = BeanDefinitionBuilder
				.genericBeanDefinition(dataSourceProperties.getFactoryBeanName());

		propertyMap.forEach((propertyName, propertyValue) -> {
			Field field = ReflectionUtils.findField(dataSourceProperties.getClass(),
					propertyName);
			if (field != null) {
				if (DATATYPE_FIELD.equals(propertyName)) {
					String dataType = StringUtils
							.trimAllWhitespace(propertyValue.toString());
					if (CUSTOM_DATATYPE.equals(dataType)) {
						try {
							if (StringUtils
									.isEmpty(dataSourceProperties.getConverterClass())) {
								throw new RuntimeException(
										"[Sentinel Starter] DataSource " + dataSourceName
												+ "dataType is custom, please set converter-class "
												+ "property");
							}
							// construct custom Converter with 'converterClass'
							// configuration and register
							String customConvertBeanName = "sentinel-"
									+ dataSourceProperties.getConverterClass();
							if (!beanFactory.containsBean(customConvertBeanName)) {
								beanFactory.registerBeanDefinition(customConvertBeanName,
										BeanDefinitionBuilder
												.genericBeanDefinition(
														Class.forName(dataSourceProperties
																.getConverterClass()))
												.getBeanDefinition());
							}
							builder.addPropertyReference("converter",
									customConvertBeanName);
						}
						catch (ClassNotFoundException e) {
							logger.error("[Sentinel Starter] DataSource " + dataSourceName
									+ " handle "
									+ dataSourceProperties.getClass().getSimpleName()
									+ " error, class name: "
									+ dataSourceProperties.getConverterClass());
							throw new RuntimeException(
									"[Sentinel Starter] DataSource " + dataSourceName
											+ " handle "
											+ dataSourceProperties.getClass()
													.getSimpleName()
											+ " error, class name: "
											+ dataSourceProperties.getConverterClass(),
									e);
						}
					}
					else {
						if (!dataTypeList.contains(StringUtils
								.trimAllWhitespace(propertyValue.toString()))) {
							throw new RuntimeException("[Sentinel Starter] DataSource "
									+ dataSourceName + " dataType: " + propertyValue
									+ " is not support now. please using these types: "
									+ dataTypeList.toString());
						}
						// converter type now support xml or json.
						// The bean name of these converters wrapped by
						// 'sentinel-{converterType}-converter'
						builder.addPropertyReference("converter",
								"sentinel-" + propertyValue.toString() + "-converter");
					}
				}
				else if (CONVERTERCLASS_FIELD.equals(propertyName)) {
					return;
				}
				else {
					// wired properties
					Optional.ofNullable(propertyValue)
							.ifPresent(v -> builder.addPropertyValue(propertyName, v));
				}
			}
		});

		beanFactory.registerBeanDefinition(dataSourceName, builder.getBeanDefinition());
		// init in Spring
		AbstractDataSource newDataSource = (AbstractDataSource) beanFactory
				.getBean(dataSourceName);
		// commercialization
		if (!StringUtils.isEmpty(System.getProperties()
				.getProperty(SentinelDataSourceConstants.NACOS_DATASOURCE_ENDPOINT))) {
			if (dataSourceName.contains(SentinelConstants.FLOW_DATASOURCE_NAME)) {
				FlowRuleManager.register2Property(newDataSource.getProperty());
			}
			else if (dataSourceName.contains(SentinelConstants.DEGRADE_DATASOURCE_NAME)) {
				DegradeRuleManager.register2Property(newDataSource.getProperty());
			}
		}
		dataSourceBeanNameList.add(dataSourceName);
	}

	private Class getAndCheckRuleType(Object ruleConfig, String dataSourceName) {
		if (rulesList.contains(ruleConfig.getClass())) {
			logger.info("[Sentinel Starter] DataSource {} load {} {}", dataSourceName, 1,
					ruleConfig.getClass().getSimpleName());
			return ruleConfig.getClass();
		}
		else if (ruleConfig instanceof List) {
			List convertedRuleList = (List) ruleConfig;
			if (CollectionUtils.isEmpty(convertedRuleList)) {
				logger.warn("[Sentinel Starter] DataSource {} rule list is empty.",
						dataSourceName);
				return null;
			}
			if (convertedRuleList.stream()
					.allMatch(rule -> rulesList.contains(rule.getClass()))) {
				if (rulesList.contains(convertedRuleList.get(0).getClass())
						&& convertedRuleList.stream()
								.filter(rule -> rule.getClass() == convertedRuleList
										.get(0).getClass())
								.toArray().length == convertedRuleList.size()) {
					logger.info("[Sentinel Starter] DataSource {} load {} {}",
							dataSourceName, convertedRuleList.size(),
							convertedRuleList.get(0).getClass().getSimpleName());
					return convertedRuleList.get(0).getClass();
				}
				else {
					logger.warn(
							"[Sentinel Starter] DataSource {} all rules are not same rule type and it will not be used. "
									+ "Rule List: {}",
							dataSourceName, convertedRuleList.toString());
				}
			}
			else {
				List<Class> classList = (List<Class>) convertedRuleList.stream()
						.map(Object::getClass).collect(Collectors.toList());
				logger.error("[Sentinel Starter] DataSource " + dataSourceName
						+ " rule class is invalid. Class List: " + classList);
				throw new RuntimeException(
						"[Sentinel Starter] DataSource " + dataSourceName
								+ " rule class is invalid. Class List: " + classList);
			}
		}
		else {
			logger.error("[Sentinel Starter] DataSource " + dataSourceName
					+ " rule class is invalid. Class: " + ruleConfig.getClass());
			throw new RuntimeException("[Sentinel Starter] DataSource " + dataSourceName
					+ " rule class is invalid. Class: " + ruleConfig.getClass());
		}
		return null;
	}

	public List<String> getDataSourceBeanNameList() {
		return dataSourceBeanNameList;
	}

}
