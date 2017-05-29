/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.unsafe.impl.internal.dragons;

/**
 * A MemoryAllocator is simple: it only allocates memory, until it itself is finalizable and frees it all in one go.
 */
public interface MemoryAllocator
{
    static MemoryAllocator createAllocator( long expectedMaxMemory, long alignment )
    {
        return new GrabAllocator( expectedMaxMemory, alignment );
    }

    /**
     * @return The sum, in bytes, of all the memory currently allocating through this allocator.
     */
    long sumUsedMemory();

    /**
     * Allocate a contiguous, aligned region of memory of the given size in bytes.
     * @param bytes the number of bytes to allocate.
     * @return A pointer to the allocated memory.
     * @throws OutOfMemoryError if the requested memory could not be allocated.
     */
    long allocateAligned( long bytes );
}
