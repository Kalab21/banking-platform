package com.bankingplatform.application.exception;

import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;

/**
 * A status change that the application's lifecycle does not permit.
 *
 * <p>Names both ends of the refused move. "Cannot move" on its own leaves the
 * reader guessing which half was wrong, and these refusals are most often read
 * in a log some time after the request.
 */
public class IllegalApplicationTransitionException extends ApplicationException {

    public IllegalApplicationTransitionException(ApplicationType type,
                                                 ApplicationStatus from,
                                                 ApplicationStatus to) {
        super("A " + type + " application cannot move from " + from + " to " + to);
    }
}
