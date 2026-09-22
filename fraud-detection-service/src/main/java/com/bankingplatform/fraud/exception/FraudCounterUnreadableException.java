package com.bankingplatform.fraud.exception;

/**
 * A fraud counter in Redis held something this service did not write.
 *
 * <p>Thrown rather than absorbed, because every other answer is worse. The
 * velocity and failed-payment counters are inputs to a risk score that can
 * raise an alert and freeze a live customer's account, so a corrupted counter
 * has two bad readings and no safe one: treat it as low and the control is
 * silently switched off for that account, treat it as high and the platform
 * manufactures fraud evidence against a customer from a storage fault.
 *
 * <p>Neither is a fact about the customer. It is a fact about the store, so it
 * is reported as one. Thrown from inside the listener's transaction, it rolls
 * back the processed-event claim and reaches the container error handler,
 * which retries and then dead-letters — the path that already exists for
 * operational failures, where an operator can see it.
 */
public class FraudCounterUnreadableException extends RuntimeException {

    public FraudCounterUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
