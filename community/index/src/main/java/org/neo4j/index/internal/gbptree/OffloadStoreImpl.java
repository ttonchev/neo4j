/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.index.internal.gbptree;

import java.io.IOException;

import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.util.VisibleForTesting;

import static java.lang.String.format;
import static org.neo4j.index.internal.gbptree.PageCursorUtil.checkOutOfBounds;

public class OffloadStoreImpl<KEY,VALUE> implements OffloadStore<KEY,VALUE>
{
    private static final int SIZE_KEY_SIZE = Integer.BYTES;
    private static final int SIZE_VALUE_SIZE = Integer.BYTES;
    private final Layout<KEY,VALUE> layout;
    private final IdProvider idProvider;
    private final PageCursorFactory pcFactory;
    private final OffloadIdValidator offloadIdValidator;
    private final int maxEntrySize;

    OffloadStoreImpl( Layout<KEY,VALUE> layout, IdProvider idProvider, PageCursorFactory pcFactory, OffloadIdValidator offloadIdValidator, int pageSize )
    {
        this.layout = layout;
        this.idProvider = idProvider;
        this.pcFactory = pcFactory;
        this.offloadIdValidator = offloadIdValidator;
        this.maxEntrySize = maxEntrySizeFromPageSize( pageSize );
    }

    @Override
    public int maxEntrySize()
    {
        return maxEntrySize;
    }

    @Override
    public void readKey( long offloadId, KEY into ) throws IOException
    {
        validateOffloadId( offloadId );

        try ( PageCursor cursor = pcFactory.create( offloadId, PagedFile.PF_SHARED_READ_LOCK ) )
        {
            do
            {
                placeCursorAtOffloadId( cursor, offloadId );

                int keySize = cursor.getInt();
                int valueSize = cursor.getInt();
                if ( keyValueSizeTooLarge( keySize, valueSize ) || keySize < 0 || valueSize < 0 )
                {
                    readUnreliableKeyValueSize( cursor, keySize, valueSize );
                    continue;
                }
                layout.readKey( cursor, into, keySize );
            }
            while ( cursor.shouldRetry() );
            checkOutOfBoundsAndClosed( cursor );
            cursor.checkAndClearCursorException();
        }
    }

    @Override
    public void readKeyValue( long offloadId, KEY key, VALUE value ) throws IOException
    {
        validateOffloadId( offloadId );

        try ( PageCursor cursor = pcFactory.create( offloadId, PagedFile.PF_SHARED_READ_LOCK ) )
        {
            do
            {
                placeCursorAtOffloadId( cursor, offloadId );

                int keySize = cursor.getInt();
                int valueSize = cursor.getInt();
                if ( keyValueSizeTooLarge( keySize, valueSize ) || keySize < 0 )
                {
                    readUnreliableKeyValueSize( cursor, keySize, valueSize );
                    continue;
                }
                layout.readKey( cursor, key, keySize );
                layout.readValue( cursor, value, valueSize );
            }
            while ( cursor.shouldRetry() );
            checkOutOfBoundsAndClosed( cursor );
            cursor.checkAndClearCursorException();
        }
    }

    @Override
    public void readValue( long offloadId, VALUE into ) throws IOException
    {
        validateOffloadId( offloadId );

        try ( PageCursor cursor = pcFactory.create( offloadId, PagedFile.PF_SHARED_READ_LOCK ) )
        {
            do
            {
                placeCursorAtOffloadId( cursor, offloadId );

                int keySize = cursor.getInt();
                int valueSize = cursor.getInt();
                if ( keyValueSizeTooLarge( keySize, valueSize ) || keySize < 0 )
                {
                    readUnreliableKeyValueSize( cursor, keySize, valueSize );
                    continue;
                }
                cursor.setOffset( cursor.getOffset() + keySize );
                layout.readValue( cursor, into, valueSize );
            }
            while ( cursor.shouldRetry() );
            checkOutOfBoundsAndClosed( cursor );
            cursor.checkAndClearCursorException();
        }
    }

    @Override
    public long writeKey( KEY key, long stableGeneration, long unstableGeneration ) throws IOException
    {
        int keySize = layout.keySize( key );
        long newId = acquireNewId( keySize, stableGeneration, unstableGeneration );
        try ( PageCursor cursor = pcFactory.create( newId, PagedFile.PF_SHARED_WRITE_LOCK ) )
        {
            placeCursorAtOffloadId( cursor, newId );

            putKeyValueSize( cursor, keySize, 0 );
            layout.writeKey( cursor, key );
            return newId;
        }
    }

    @Override
    public long writeKeyValue( KEY key, VALUE value, long stableGeneration, long unstableGeneration ) throws IOException
    {
        int keySize = layout.keySize( key );
        int valueSize = layout.valueSize( value );
        long newId = acquireNewId( keySize + valueSize, stableGeneration, unstableGeneration );
        try ( PageCursor cursor = pcFactory.create( newId, PagedFile.PF_SHARED_WRITE_LOCK ) )
        {
            placeCursorAtOffloadId( cursor, newId );

            putKeyValueSize( cursor, keySize, valueSize );
            layout.writeKey( cursor, key );
            layout.writeValue( cursor, value );
            return newId;
        }
    }

    @Override
    public void free( long offloadId )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @VisibleForTesting
    static int maxEntrySizeFromPageSize( int pageSize )
    {
        return pageSize - SIZE_KEY_SIZE - SIZE_VALUE_SIZE;
    }

    private void putKeyValueSize( PageCursor cursor, int keySize, int valueSize )
    {
        cursor.putInt( keySize );
        cursor.putInt( valueSize );
    }

    private long acquireNewId( int keySize, long stableGeneration, long unstableGeneration ) throws IOException
    {
        return idProvider.acquireNewId( stableGeneration, unstableGeneration );
    }

    private void placeCursorAtOffloadId( PageCursor cursor, long offloadId ) throws IOException
    {
        PageCursorUtil.goTo( cursor, "offload page", offloadId );
    }

    /**
     * todo: Copied from SeekCursor. Unify usage
     */
    private void checkOutOfBoundsAndClosed( PageCursor cursor )
    {
        try
        {
            checkOutOfBounds( cursor );
        }
        catch ( TreeInconsistencyException e )
        {
            // Only check the closed status here when we get an out of bounds to avoid making
            // this check for every call to next.
            throw e;
        }
    }

    private boolean keyValueSizeTooLarge( int keySize, int valueSize )
    {
        return keySize > maxEntrySize || valueSize > maxEntrySize || (keySize + valueSize) > maxEntrySize;
    }

    private void readUnreliableKeyValueSize( PageCursor cursor, int keySize, int valueSize )
    {
        cursor.setCursorException( format( "Read unreliable key, id=%d, keySize=%d, valueSize=%d", cursor.getCurrentPageId(), keySize, valueSize ) );
    }

    private void validateOffloadId( long offloadId ) throws IOException
    {
        if ( !offloadIdValidator.valid( offloadId ) )
        {
            throw new IOException( String.format( "Offload id %d is outside of valid range, ", offloadId ) );
        }
    }
}
