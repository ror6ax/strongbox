package org.carlspring.strongbox.authentication.api.impl.ldap;

import org.carlspring.strongbox.authentication.api.Authenticator;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

/**
 * @author Przemyslaw Fusik
 */
public class LdapAuthenticator
        implements Authenticator
{

    @Inject
    LdapAuthenticationProvider authenticationProvider;

    @Nonnull
    @Override
    public AuthenticationProvider getAuthenticationProvider()
    {
        return authenticationProvider;
    }
}
