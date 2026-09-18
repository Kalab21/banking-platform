package com.bankingplatform.user.model;

/**
 * How far a customer's identity information has got.
 *
 * <p>Deliberately short. There is no identity-verification provider behind this
 * system, so there is no honest way to reach a "verified" state: a Social
 * Security number that matches the expected shape has been checked for shape and
 * nothing more.
 */
public enum IdentityStatus {

    /** The customer supplied their details. Nothing has confirmed them. */
    SUBMITTED
}
