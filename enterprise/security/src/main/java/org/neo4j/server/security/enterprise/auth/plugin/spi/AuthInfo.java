/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth.plugin.spi;

import java.io.Serializable;
import java.util.Collection;

/**
 * TODO
 */
public interface AuthInfo extends Serializable
{
    /**
     * TODO
     */
    Object getPrincipal();

    /**
     * TODO
     */
    Object getCredentials();

    /**
     * TODO
     */
    Collection<String> getRoles();

    static AuthInfo of( Object principal, Object credentials, Collection<String> roles )
    {
        return new AuthInfo()
        {
            @Override
            public Object getPrincipal()
            {
                return principal;
            }

            @Override
            public Object getCredentials()
            {
                return credentials;
            }

            @Override
            public Collection<String> getRoles()
            {
                return roles;
            }
        };
    }

    static AuthInfo of( Object principal, Collection<String> roles )
    {
        return new AuthInfo()
        {
            @Override
            public Object getPrincipal()
            {
                return principal;
            }

            @Override
            public Object getCredentials()
            {
                return null;
            }

            @Override
            public Collection<String> getRoles()
            {
                return roles;
            }
        };
    }
}
