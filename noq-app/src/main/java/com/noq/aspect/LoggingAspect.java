package com.noq.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AOP demo: logging is a "cross-cutting concern" - every service needs it, but it is not business logic.
 * Instead of writing log lines in every method, this ONE class wraps every public method of every
 * class in com.noq.service and records how long it took and whether it failed.
 * <p>
 * Method ARGUMENTS and RETURN VALUES are deliberately NOT logged: they can contain pickup OTPs.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    // Pointcut: any public method, any return type, in com.noq.service or its sub-packages
    @Around("execution(public * com.noq.service..*(..))")
    public Object logExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getDeclaringType().getSimpleName() + "." + joinPoint.getSignature().getName();
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed(); // run the real method
            log.info("{} took {} ms", method, (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Throwable ex) {
            log.warn("{} failed after {} ms: {}", method, (System.nanoTime() - start) / 1_000_000, ex.getMessage());
            throw ex;
        }
    }
}
