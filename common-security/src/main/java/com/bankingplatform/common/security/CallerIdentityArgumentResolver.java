package com.bankingplatform.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link CallerIdentity} to any controller method that declares it,
 * so no controller parses identity headers itself.
 *
 * <p>Resolution fails closed. A user-facing endpoint that asks for a caller and
 * does not get a well-formed one is rejected, rather than falling back to a
 * null or anonymous caller that later checks would wave through.
 */
public class CallerIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new MissingCallerIdentityException("No caller identity on this request");
        }

        Long userId = parseUserId(request.getHeader(CallerIdentityHeaders.USER_ID));
        Role role = Role.parse(request.getHeader(CallerIdentityHeaders.USER_ROLE))
                .orElseThrow(() -> new MissingCallerIdentityException("No usable caller role on this request"));

        String username = request.getHeader(CallerIdentityHeaders.USERNAME);

        return new CallerIdentity(userId, username, role);
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MissingCallerIdentityException("No caller id on this request");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // A non-numeric id is a malformed request, not an anonymous one.
            throw new MissingCallerIdentityException("Malformed caller id on this request");
        }
    }
}
