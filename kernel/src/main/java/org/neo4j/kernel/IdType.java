/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel;

public enum IdType
{
    NODE( 35 ),
    RELATIONSHIP( 35 ),
    PROPERTY( 36 ),
    STRING_BLOCK( 36 ),
    ARRAY_BLOCK( 36 ),
    PROPERTY_INDEX,
    PROPERTY_INDEX_BLOCK,
    RELATIONSHIP_TYPE( 16 ),
    RELATIONSHIP_TYPE_BLOCK,
    NEOSTORE_BLOCK;
    
    private final long max;
    
    private IdType()
    {
        this( 32 );
    }

    private IdType( int bits )
    {
        this.max = (long)Math.pow( 2, bits )-1;
    }
    
    public long getMaxValue()
    {
        return this.max;
    }
}
