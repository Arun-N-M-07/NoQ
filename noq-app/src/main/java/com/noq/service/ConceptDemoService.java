package com.noq.service;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Viva demonstrations that normal application code does not need:
 * the raw Hibernate SessionFactory / Session / Transaction API, and the Spring IoC container.
 */
@Service
public class ConceptDemoService {

    private final EntityManagerFactory entityManagerFactory;
    private final ApplicationContext applicationContext;

    public ConceptDemoService(EntityManagerFactory entityManagerFactory, ApplicationContext applicationContext) {
        this.entityManagerFactory = entityManagerFactory;
        this.applicationContext = applicationContext;
    }

    /**
     * SessionFactory -> Session -> Transaction, by hand.
     * We UNWRAP the SessionFactory that Spring Boot already built (we do not create a second one).
     */
    public Map<String, Object> hibernateSessionDemo() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class); // heavyweight, one per app
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entitiesMappedBySessionFactory", sessionFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getName).sorted().toList());

        try (Session session = sessionFactory.openSession()) { // lightweight, one per unit of work
            Transaction tx = session.beginTransaction();       // commit or roll back as one unit
            result.put("transactionActive", tx.isActive());
            try {
                Long activeItems = session.createQuery(
                        "select count(m) from MenuItem m where m.active = true", Long.class).getSingleResult();
                List<String> topThree = session.createQuery(
                                "select m.name from MenuItem m order by m.popularityCount desc, m.id asc", String.class)
                        .setMaxResults(3).getResultList();
                // Inheritance mapping: type(p) reads the discriminator column and returns the Java subclass
                List<Object[]> byType = session.createQuery(
                        "select type(p), count(p) from Payment p group by type(p)", Object[].class).getResultList();
                tx.commit();

                Map<String, Long> paymentsByType = new LinkedHashMap<>();
                for (Object[] row : byType) {
                    paymentsByType.put(((Class<?>) row[0]).getSimpleName(), (Long) row[1]);
                }
                result.put("sessionOpen", session.isOpen());
                result.put("transactionCommitted", true);
                result.put("hqlActiveMenuItems", activeItems);
                result.put("hqlTopThreeByPopularity", topThree);
                result.put("hqlPaymentsBySubclass", paymentsByType);
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
        return result;
    }

    /** Shows that ApplicationContext IS-A BeanFactory and that our services are singletons wrapped in proxies. */
    public Map<String, Object> springContainerDemo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationContextClass", applicationContext.getClass().getName());
        result.put("applicationContextIsABeanFactory", applicationContext instanceof BeanFactory);
        result.put("totalBeans", applicationContext.getBeanDefinitionCount());
        List<String> noqBeans = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType != null && beanType.getName().startsWith("com.noq")) {
                noqBeans.add(beanName);
            }
        }
        Collections.sort(noqBeans);
        result.put("noqBeans", noqBeans);
        Object orderService = applicationContext.getBean(OrderService.class);
        result.put("orderServiceIsSingleton", orderService == applicationContext.getBean(OrderService.class));
        result.put("orderServiceIsAopProxy", AopUtils.isAopProxy(orderService)); // proxy adds @Transactional + logging
        result.put("orderServiceProxyClass", orderService.getClass().getName());
        return result;
    }
}
