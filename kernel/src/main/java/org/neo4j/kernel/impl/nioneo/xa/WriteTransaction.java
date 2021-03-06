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
package org.neo4j.kernel.impl.nioneo.xa;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.core.PropertyIndex;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyData;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexData;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyType;
import org.neo4j.kernel.impl.nioneo.store.Record;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeData;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeStore;
import org.neo4j.kernel.impl.nioneo.xa.Command.PropertyCommand;
import org.neo4j.kernel.impl.persistence.NeoStoreTransaction;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.LockType;
import org.neo4j.kernel.impl.transaction.xaframework.XaCommand;
import org.neo4j.kernel.impl.transaction.xaframework.XaConnection;
import org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog;
import org.neo4j.kernel.impl.transaction.xaframework.XaTransaction;
import org.neo4j.kernel.impl.util.ArrayMap;
import org.neo4j.kernel.impl.util.RelIdArray;
import org.neo4j.kernel.impl.util.RelIdArray.DirectionWrapper;

/**
 * Transaction containing {@link Command commands} reflecting the operations
 * performed in the transaction.
 */
public class WriteTransaction extends XaTransaction implements NeoStoreTransaction
{
    private final Map<Long,NodeRecord> nodeRecords =
        new HashMap<Long,NodeRecord>();
    private final Map<Long,PropertyRecord> propertyRecords =
        new HashMap<Long,PropertyRecord>();
    private final Map<Long,RelationshipRecord> relRecords =
        new HashMap<Long,RelationshipRecord>();
    private final Map<Integer,RelationshipTypeRecord> relTypeRecords =
        new HashMap<Integer,RelationshipTypeRecord>();
    private final Map<Integer,PropertyIndexRecord> propIndexRecords =
        new HashMap<Integer,PropertyIndexRecord>();

    private final ArrayList<Command.NodeCommand> nodeCommands =
        new ArrayList<Command.NodeCommand>();
    private final ArrayList<Command.PropertyCommand> propCommands =
        new ArrayList<Command.PropertyCommand>();
    private final ArrayList<Command.PropertyIndexCommand> propIndexCommands =
        new ArrayList<Command.PropertyIndexCommand>();
    private final ArrayList<Command.RelationshipCommand> relCommands =
        new ArrayList<Command.RelationshipCommand>();
    private final ArrayList<Command.RelationshipTypeCommand> relTypeCommands =
        new ArrayList<Command.RelationshipTypeCommand>();

    private final NeoStore neoStore;
    private boolean committed = false;
    private boolean prepared = false;

    private final LockReleaser lockReleaser;
    private final LockManager lockManager;
    private XaConnection xaConnection;

    WriteTransaction( int identifier, XaLogicalLog log, NeoStore neoStore,
        LockReleaser lockReleaser, LockManager lockManager )
    {
        super( identifier, log );
        this.neoStore = neoStore;
        this.lockReleaser = lockReleaser;
        this.lockManager = lockManager;
    }

    @Override
    public boolean isReadOnly()
    {
        if ( isRecovered() )
        {
            if ( nodeCommands.size() == 0 && propCommands.size() == 0 &&
                relCommands.size() == 0 && relTypeCommands.size() == 0 &&
                propIndexCommands.size() == 0 )
            {
                return true;
            }
            return false;
        }
        if ( nodeRecords.size() == 0 && relRecords.size() == 0 &&
            relTypeRecords.size() == 0 && propertyRecords.size() == 0 &&
            propIndexRecords.size() == 0 )
        {
            return true;
        }
        return false;
    }

    @Override
    public void doAddCommand( XaCommand command )
    {
        // override
    }

    @Override
    protected void doPrepare() throws XAException
    {
        if ( committed )
        {
            throw new XAException( "Cannot prepare committed transaction["
                + getIdentifier() + "]" );
        }
        if ( prepared )
        {
            throw new XAException( "Cannot prepare prepared transaction["
                + getIdentifier() + "]" );
        }
        // generate records then write to logical log via addCommand method
        prepared = true;
        for ( RelationshipTypeRecord record : relTypeRecords.values() )
        {
            Command.RelationshipTypeCommand command =
                new Command.RelationshipTypeCommand(
                    neoStore.getRelationshipTypeStore(), record );
            relTypeCommands.add( command );
            addCommand( command );
        }
        for ( NodeRecord record : nodeRecords.values() )
        {
            if ( !record.inUse() && record.getNextRel() !=
                Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                throw new InvalidRecordException( "Node record " + record
                    + " still has relationships" );
            }
            Command.NodeCommand command = new Command.NodeCommand(
                neoStore.getNodeStore(), record );
            nodeCommands.add( command );
            if ( !record.inUse() )
            {
                removeNodeFromCache( record.getId() );
            }
            addCommand( command );
        }
        for ( RelationshipRecord record : relRecords.values() )
        {
            Command.RelationshipCommand command =
                new Command.RelationshipCommand(
                    neoStore.getRelationshipStore(), record );
            relCommands.add( command );
            if ( !record.inUse() )
            {
                removeRelationshipFromCache( record.getId() );
            }
            addCommand( command );
        }
        for ( PropertyIndexRecord record : propIndexRecords.values() )
        {
            Command.PropertyIndexCommand command =
                new Command.PropertyIndexCommand(
                    neoStore.getPropertyStore().getIndexStore(), record );
            propIndexCommands.add( command );
            addCommand( command );
        }
        for ( PropertyRecord record : propertyRecords.values() )
        {
            Command.PropertyCommand command = new Command.PropertyCommand(
                neoStore.getPropertyStore(), record );
            propCommands.add( command );
            addCommand( command );
        }
    }

    @Override
    protected void injectCommand( XaCommand xaCommand )
    {
        if ( xaCommand instanceof Command.NodeCommand )
        {
            nodeCommands.add( (Command.NodeCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipCommand )
        {
            relCommands.add( (Command.RelationshipCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyCommand )
        {
            propCommands.add( (Command.PropertyCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyIndexCommand )
        {
            propIndexCommands.add( (Command.PropertyIndexCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipTypeCommand )
        {
            relTypeCommands.add( (Command.RelationshipTypeCommand) xaCommand );
        }
        else
        {
            throw new IllegalArgumentException( "Unknown command " + xaCommand );
        }
    }

    @Override
    public void doRollback() throws XAException
    {
        if ( committed )
        {
            throw new XAException( "Cannot rollback partialy commited "
                + "transaction[" + getIdentifier() + "]. Recover and "
                + "commit" );
        }
        try
        {
            for ( RelationshipTypeRecord record : relTypeRecords.values() )
            {
                if ( record.isCreated() )
                {
                    getRelationshipTypeStore().freeId( record.getId() );
                    for ( DynamicRecord dynamicRecord : record.getTypeRecords() )
                    {
                        if ( dynamicRecord.isCreated() )
                        {
                            getRelationshipTypeStore().freeBlockId(
                                (int) dynamicRecord.getId() );
                        }
                    }
                }
                removeRelationshipTypeFromCache( record.getId() );
            }
            for ( NodeRecord record : nodeRecords.values() )
            {
                if ( record.isCreated() )
                {
                    getNodeStore().freeId( record.getId() );
                }
                removeNodeFromCache( record.getId() );
            }
            for ( RelationshipRecord record : relRecords.values() )
            {
                if ( record.isCreated() )
                {
                    getRelationshipStore().freeId( record.getId() );
                }
                removeRelationshipFromCache( record.getId() );
            }
            for ( PropertyIndexRecord record : propIndexRecords.values() )
            {
                if ( record.isCreated() )
                {
                    getPropertyStore().getIndexStore().freeId( record.getId() );
                    for ( DynamicRecord dynamicRecord : record.getKeyRecords() )
                    {
                        if ( dynamicRecord.isCreated() )
                        {
                            getPropertyStore().getIndexStore().freeBlockId(
                                (int) dynamicRecord.getId() );
                        }
                    }
                }
            }
            for ( PropertyRecord record : propertyRecords.values() )
            {
                if ( record.getNodeId() != -1 )
                {
                    removeNodeFromCache( record.getNodeId() );
                }
                else if ( record.getRelId() != -1 )
                {
                    removeRelationshipFromCache( record.getRelId() );
                }
                if ( record.isCreated() )
                {
                    getPropertyStore().freeId( record.getId() );
                    for ( DynamicRecord dynamicRecord : record
                        .getValueRecords() )
                    {
                        if ( dynamicRecord.isCreated() )
                        {
                            if ( dynamicRecord.getType() ==
                                PropertyType.STRING.intValue() )
                            {
                                getPropertyStore().freeStringBlockId(
                                    dynamicRecord.getId() );
                            }
                            else if ( dynamicRecord.getType() ==
                                PropertyType.ARRAY.intValue() )
                            {
                                getPropertyStore().freeArrayBlockId(
                                    dynamicRecord.getId() );
                            }
                            else
                            {
                                throw new InvalidRecordException(
                                    "Unknown type on " + dynamicRecord );
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            nodeRecords.clear();
            propertyRecords.clear();
            relRecords.clear();
            relTypeRecords.clear();
            propIndexRecords.clear();

            nodeCommands.clear();
            propCommands.clear();
            propIndexCommands.clear();
            relCommands.clear();
            relTypeCommands.clear();
        }
    }

    private void removeRelationshipTypeFromCache( int id )
    {
        lockReleaser.removeRelationshipTypeFromCache( id );
    }

    private void removeRelationshipFromCache( long id )
    {
        lockReleaser.removeRelationshipFromCache( id );
    }

    private void removeNodeFromCache( long id )
    {
        lockReleaser.removeNodeFromCache( id );
    }

    private void addRelationshipType( int id )
    {
        setRecovered();
        RelationshipTypeData type;
        if ( isRecovered() )
        {
            type = neoStore.getRelationshipTypeStore().getRelationshipType(
                id, true );
        }
        else
        {
            type = neoStore.getRelationshipTypeStore().getRelationshipType(
                id );
        }
        lockReleaser.addRelationshipType( type );
    }

    private void addPropertyIndexCommand( int id )
    {
        PropertyIndexData index;
        if ( isRecovered() )
        {
            index =
                neoStore.getPropertyStore().getIndexStore().getPropertyIndex(
                    id, true );
        }
        else
        {
            index =
                neoStore.getPropertyStore().getIndexStore().getPropertyIndex(
                    id );
        }
        lockReleaser.addPropertyIndex( index );
    }

    @Override
    public void doCommit() throws XAException
    {
        if ( !isRecovered() && !prepared )
        {
            throw new XAException( "Cannot commit non prepared transaction["
                + getIdentifier() + "]" );
        }
        if ( isRecovered() )
        {
            commitRecovered();
            return;
        }
        if ( !isRecovered() && getCommitTxId() != neoStore.getLastCommittedTx() + 1 )
        {
            throw new RuntimeException( "Tx id: " + getCommitTxId() +
                    " not next transaction (" + neoStore.getLastCommittedTx() + ")" );
        }
        try
        {
            committed = true;
            CommandSorter sorter = new CommandSorter();
            // reltypes
            java.util.Collections.sort( relTypeCommands, sorter );
            for ( Command.RelationshipTypeCommand command : relTypeCommands )
            {
                command.execute();
            }
            // property keys
            java.util.Collections.sort( propIndexCommands, sorter );
            for ( Command.PropertyIndexCommand command : propIndexCommands )
            {
                command.execute();
            }

            // primitives
            java.util.Collections.sort( nodeCommands, sorter );
            java.util.Collections.sort( relCommands, sorter );
            java.util.Collections.sort( propCommands, sorter );
            executeCreated( propCommands, relCommands, nodeCommands );
            executeModified( propCommands, relCommands, nodeCommands );
            executeDeleted( propCommands, relCommands, nodeCommands );
            lockReleaser.commitCows();
            neoStore.setLastCommittedTx( getCommitTxId() );
        }
        finally
        {
            nodeRecords.clear();
            propertyRecords.clear();
            relRecords.clear();
            relTypeRecords.clear();
            propIndexRecords.clear();

            nodeCommands.clear();
            propCommands.clear();
            propIndexCommands.clear();
            relCommands.clear();
            relTypeCommands.clear();
        }
    }

    private static void executeCreated( ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( command.isCreated() && !command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private static void executeModified( ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( !command.isCreated() && !command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private static void executeDeleted( ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private void commitRecovered()
    {
        try
        {
            committed = true;
            CommandSorter sorter = new CommandSorter();
            // property index
            java.util.Collections.sort( propIndexCommands, sorter );
            for ( Command.PropertyIndexCommand command : propIndexCommands )
            {
                command.execute();
                addPropertyIndexCommand( (int) command.getKey() );
            }
            // properties
            java.util.Collections.sort( propCommands, sorter );
            for ( Command.PropertyCommand command : propCommands )
            {
                command.execute();
                removePropertyFromCache( command );
            }
            // reltypes
            java.util.Collections.sort( relTypeCommands, sorter );
            for ( Command.RelationshipTypeCommand command : relTypeCommands )
            {
                command.execute();
                addRelationshipType( (int) command.getKey() );
            }
            // relationships
            java.util.Collections.sort( relCommands, sorter );
            for ( Command.RelationshipCommand command : relCommands )
            {
                command.execute();
                removeRelationshipFromCache( command.getKey() );
                if ( true /* doesn't work: command.isRemove(), the log doesn't contain the nodes */)
                {
                    removeNodeFromCache( command.getFirstNode() );
                    removeNodeFromCache( command.getSecondNode() );
                }
            }
            // nodes
            java.util.Collections.sort( nodeCommands, sorter );
            for ( Command.NodeCommand command : nodeCommands )
            {
                command.execute();
                removeNodeFromCache( command.getKey() );
            }
            neoStore.setRecoveredStatus( true );
            try
            {
                neoStore.setLastCommittedTx( getCommitTxId() );
            }
            finally
            {
                neoStore.setRecoveredStatus( false );
            }
            neoStore.getIdGeneratorFactory().updateIdGenerators( neoStore );
        }
        finally
        {
            nodeRecords.clear();
            propertyRecords.clear();
            relRecords.clear();
            relTypeRecords.clear();
            propIndexRecords.clear();

            nodeCommands.clear();
            propCommands.clear();
            propIndexCommands.clear();
            relCommands.clear();
            relTypeCommands.clear();
        }
    }


    private void removePropertyFromCache( PropertyCommand command )
    {
        long nodeId = command.getNodeId();
        long relId = command.getRelId();
        if ( nodeId != -1 )
        {
            removeNodeFromCache( nodeId );
        }
        else if ( relId != -1 )
        {
            removeRelationshipFromCache( relId );
        }
        // else means record value did not change
    }

    private RelationshipTypeStore getRelationshipTypeStore()
    {
        return neoStore.getRelationshipTypeStore();
    }

    private int getRelGrabSize()
    {
        return neoStore.getRelationshipGrabSize();
    }

    private NodeStore getNodeStore()
    {
        return neoStore.getNodeStore();
    }

    private RelationshipStore getRelationshipStore()
    {
        return neoStore.getRelationshipStore();
    }

    private PropertyStore getPropertyStore()
    {
        return neoStore.getPropertyStore();
    }

    public boolean nodeLoadLight( long nodeId )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord != null )
        {
            return true;
        }
        return getNodeStore().loadLightNode( nodeId );
    }

    public RelationshipRecord relLoadLight( long id )
    {
        RelationshipRecord relRecord = getRelationshipRecord( id );
        if ( relRecord != null )
        {
            // if deleted in this tx still return it
//            if ( !relRecord.inUse() )
//            {
//                return null;
//            }
            return relRecord;
        }
        relRecord = getRelationshipStore().getLightRel( id );
        if ( relRecord != null )
        {
            return relRecord;
        }
        return null;
    }

    public ArrayMap<Integer,PropertyData> nodeDelete( long nodeId )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord == null )
        {
            nodeRecord = getNodeStore().getRecord( nodeId );
            addNodeRecord( nodeRecord );
        }
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete Node[" + nodeId +
            "] since it has already been deleted." );
        }
        nodeRecord.setInUse( false );
        ArrayMap<Integer,PropertyData> propertyMap =
            new ArrayMap<Integer,PropertyData>( 9, false, true );
        long nextProp = nodeRecord.getNextProp();
        while ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord propRecord = getPropertyRecord( nextProp );
            if ( propRecord == null )
            {
                propRecord = getPropertyStore().getRecord( nextProp );
                addPropertyRecord( propRecord );
            }
            if ( propRecord.isLight() )
            {
                getPropertyStore().makeHeavy( propRecord );
            }
            if ( !propRecord.isCreated() )
            {
                if ( !propRecord.isChanged() )
                {
                    propertyMap.put( propRecord.getKeyIndexId(), propRecord.newPropertyData(
                            propertyGetValueOrNull( propRecord ) ) );
                }
                else
                {
                    // we have to re-read committed value since property has
                    // changed and old value is erased in memory
                    PropertyRecord diskValue = getPropertyStore().getRecord( propRecord.getId() );
                    getPropertyStore().makeHeavy( diskValue );
                    propertyMap.put( diskValue.getKeyIndexId(), diskValue.newPropertyData(
                            propertyGetValueOrNull( propRecord ) ) );
                }
            }

            nextProp = propRecord.getNextProp();
            propRecord.setInUse( false );
            // TODO: update count on property index record
            for ( DynamicRecord valueRecord : propRecord.getValueRecords() )
            {
                valueRecord.setInUse( false );
            }
        }
        return propertyMap;
    }

    public ArrayMap<Integer,PropertyData> relDelete( long id )
    {
        RelationshipRecord record = getRelationshipRecord( id );
        if ( record == null )
        {
            record = getRelationshipStore().getRecord( id );
            addRelationshipRecord( record );
        }
        if ( !record.inUse() )
        {
            throw new IllegalStateException( "Unable to delete relationship[" +
                id + "] since it is already deleted." );
        }
        ArrayMap<Integer,PropertyData> propertyMap =
            new ArrayMap<Integer,PropertyData>( 9, false, true );
        long nextProp = record.getNextProp();
        while ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord propRecord = getPropertyRecord( nextProp );
            if ( propRecord == null )
            {
                propRecord = getPropertyStore().getRecord( nextProp );
                addPropertyRecord( propRecord );
            }
            if ( propRecord.isLight() )
            {
                getPropertyStore().makeHeavy( propRecord );
            }
            if ( !propRecord.isCreated() )
            {
                if ( !propRecord.isChanged() )
                {
                    propertyMap.put( propRecord.getKeyIndexId(),
                            propRecord.newPropertyData( propertyGetValueOrNull( propRecord ) ) );
                }
                else
                {
                    // we have to re-read committed value since property has
                    // changed and old value is erased in memory
                    PropertyRecord diskValue = getPropertyStore().getRecord( propRecord.getId() );
                    getPropertyStore().makeHeavy( diskValue );
                    propertyMap.put( diskValue.getKeyIndexId(), diskValue.newPropertyData(
                            propertyGetValueOrNull( propRecord ) ) );
                }
            }
            nextProp = propRecord.getNextProp();
            propRecord.setInUse( false );
            // TODO: update count on property index record
            for ( DynamicRecord valueRecord : propRecord.getValueRecords() )
            {
                valueRecord.setInUse( false );
            }
        }
        disconnectRelationship( record );
        updateNodes( record );
        record.setInUse( false );
        return propertyMap;
    }

    private void disconnectRelationship( RelationshipRecord rel )
    {
        // update first node prev
        if ( rel.getFirstPrevRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship(
                rel.getFirstPrevRel() );
            getWriteLock( lockableRel );
            RelationshipRecord prevRel = getRelationshipRecord(
                rel.getFirstPrevRel() );
            if ( prevRel == null )
            {
                prevRel = getRelationshipStore().getRecord(
                    rel.getFirstPrevRel() );
                addRelationshipRecord( prevRel );
            }
            boolean changed = false;
            if ( prevRel.getFirstNode() == rel.getFirstNode() )
            {
                prevRel.setFirstNextRel( rel.getFirstNextRel() );
                changed = true;
            }
            if ( prevRel.getSecondNode() == rel.getFirstNode() )
            {
                prevRel.setSecondNextRel( rel.getFirstNextRel() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException(
                    prevRel + " don't match " + rel );
            }
        }
        // update first node next
        if ( rel.getFirstNextRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship(
                rel.getFirstNextRel() );
            getWriteLock( lockableRel );
            RelationshipRecord nextRel = getRelationshipRecord(
                rel.getFirstNextRel() );
            if ( nextRel == null )
            {
                nextRel = getRelationshipStore().getRecord(
                    rel.getFirstNextRel() );
                addRelationshipRecord( nextRel );
            }
            boolean changed = false;
            if ( nextRel.getFirstNode() == rel.getFirstNode() )
            {
                nextRel.setFirstPrevRel( rel.getFirstPrevRel() );
                changed = true;
            }
            if ( nextRel.getSecondNode() == rel.getFirstNode() )
            {
                nextRel.setSecondPrevRel( rel.getFirstPrevRel() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nextRel + " don't match "
                    + rel );
            }
        }
        // update second node prev
        if ( rel.getSecondPrevRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship(
                rel.getSecondPrevRel() );
            getWriteLock( lockableRel );
            RelationshipRecord prevRel = getRelationshipRecord(
                rel.getSecondPrevRel() );
            if ( prevRel == null )
            {
                prevRel = getRelationshipStore().getRecord(
                    rel.getSecondPrevRel() );
                addRelationshipRecord( prevRel );
            }
            boolean changed = false;
            if ( prevRel.getFirstNode() == rel.getSecondNode() )
            {
                prevRel.setFirstNextRel( rel.getSecondNextRel() );
                changed = true;
            }
            if ( prevRel.getSecondNode() == rel.getSecondNode() )
            {
                prevRel.setSecondNextRel( rel.getSecondNextRel() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( prevRel + " don't match " +
                    rel );
            }
        }
        // update second node next
        if ( rel.getSecondNextRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship(
                rel.getSecondNextRel() );
            getWriteLock( lockableRel );
            RelationshipRecord nextRel = getRelationshipRecord(
                rel.getSecondNextRel() );
            if ( nextRel == null )
            {
                nextRel = getRelationshipStore().getRecord(
                    rel.getSecondNextRel() );
                addRelationshipRecord( nextRel );
            }
            boolean changed = false;
            if ( nextRel.getFirstNode() == rel.getSecondNode() )
            {
                nextRel.setFirstPrevRel( rel.getSecondPrevRel() );
                changed = true;
            }
            if ( nextRel.getSecondNode() == rel.getSecondNode() )
            {
                nextRel.setSecondPrevRel( rel.getSecondPrevRel() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nextRel + " don't match " +
                    rel );
            }
        }
    }

    private void getWriteLock( Relationship lockableRel )
    {
        lockManager.getWriteLock( lockableRel );
        lockReleaser.addLockToTransaction( lockableRel, LockType.WRITE );
    }

    public long getRelationshipChainPosition( long nodeId )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord != null && nodeRecord.isCreated() )
        {
            return Record.NO_NEXT_RELATIONSHIP.intValue();
        }
        return getNodeStore().getRecord( nodeId ).getNextRel();
    }

    public Pair<Map<DirectionWrapper, Iterable<RelationshipRecord>>, Long> getMoreRelationships( long nodeId,
        long position )
    {
        return ReadTransaction.getMoreRelationships( nodeId, position, getRelGrabSize(), getRelationshipStore() );
    }

    private void updateNodes( RelationshipRecord rel )
    {
        if ( rel.getFirstPrevRel() == Record.NO_PREV_RELATIONSHIP.intValue() )
        {
            NodeRecord firstNode = getNodeRecord( rel.getFirstNode() );
            if ( firstNode == null )
            {
                firstNode = getNodeStore().getRecord( rel.getFirstNode() );
                addNodeRecord( firstNode );
            }
            firstNode.setNextRel( rel.getFirstNextRel() );
        }
        if ( rel.getSecondPrevRel() == Record.NO_PREV_RELATIONSHIP.intValue() )
        {
            NodeRecord secondNode = getNodeRecord( rel.getSecondNode() );
            if ( secondNode == null )
            {
                secondNode = getNodeStore().getRecord( rel.getSecondNode() );
                addNodeRecord( secondNode );
            }
            secondNode.setNextRel( rel.getSecondNextRel() );
        }
    }

    public void relRemoveProperty( long relId, long propertyId )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId );
        if ( relRecord == null )
        {
            relRecord = getRelationshipStore().getRecord( relId );
        }
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on relationship[" +
                relId + "] illegal since it has been deleted." );
        }
        PropertyRecord propRecord = getPropertyRecord( propertyId );
        if ( propRecord == null )
        {
            propRecord = getPropertyStore().getRecord( propertyId );
            addPropertyRecord( propRecord );
        }
        if ( !propRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete property[" +
                propertyId + "] since it is already deleted." );
        }
        propRecord.setRelId( relId );
        if ( propRecord.isLight() )
        {
            getPropertyStore().makeHeavy( propRecord );
        }

        propRecord.setInUse( false );
        // TODO: update count on property index record
        for ( DynamicRecord valueRecord : propRecord.getValueRecords() )
        {
            if ( valueRecord.inUse() )
            {
                valueRecord.setInUse( false, propRecord.getType().intValue() );
            }
        }
        long prevProp = propRecord.getPrevProp();
        long nextProp = propRecord.getNextProp();
        if ( relRecord.getNextProp() == propertyId )
        {
            relRecord.setNextProp( nextProp );
            // re-adding not a problem
            addRelationshipRecord( relRecord );
        }
        if ( prevProp != Record.NO_PREVIOUS_PROPERTY.intValue() )
        {
            PropertyRecord prevPropRecord = getPropertyRecord( prevProp );
            if ( prevPropRecord == null )
            {
                prevPropRecord = getPropertyStore().getLightRecord( prevProp );
                addPropertyRecord( prevPropRecord );
            }
            assert prevPropRecord.inUse();
            prevPropRecord.setNextProp( nextProp );
        }
        if ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord nextPropRecord = getPropertyRecord( nextProp );
            if ( nextPropRecord == null )
            {
                nextPropRecord = getPropertyStore().getLightRecord( nextProp );
                addPropertyRecord( nextPropRecord );
            }
            assert nextPropRecord.inUse();
            nextPropRecord.setPrevProp( prevProp );
        }
    }

    public ArrayMap<Integer,PropertyData> relLoadProperties( long relId,
            boolean light )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId );
        if ( relRecord != null && relRecord.isCreated() )
        {
            return null;
        }
        if ( relRecord != null )
        {
            if ( !relRecord.inUse() && !light )
            {
                throw new IllegalStateException( "Relationship[" + relId +
                        "] has been deleted in this tx" );
            }
        }
        relRecord = getRelationshipStore().getRecord( relId );
        if ( !relRecord.inUse() )
        {
            throw new InvalidRecordException( "Relationship[" + relId +
                "] not in use" );
        }
        return ReadTransaction.loadProperties( getPropertyStore(), relRecord.getNextProp() );
    }

    public ArrayMap<Integer,PropertyData> nodeLoadProperties( long nodeId, boolean light )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord != null && nodeRecord.isCreated() )
        {
            return null;
        }
        if ( nodeRecord != null )
        {
            if ( !nodeRecord.inUse() && !light )
            {
                throw new IllegalStateException( "Node[" + nodeId +
                        "] has been deleted in this tx" );
            }
        }
        nodeRecord = getNodeStore().getRecord( nodeId );
        if ( !nodeRecord.inUse() )
        {
            throw new InvalidRecordException( "Node[" + nodeId +
                "] not in use" );
        }
        return ReadTransaction.loadProperties( getPropertyStore(), nodeRecord.getNextProp() );
    }

    public Object propertyGetValueOrNull( PropertyRecord propertyRecord )
    {
        return propertyRecord.getType().getValue( propertyRecord, propertyRecord.isLight() ? null : getPropertyStore() );
    }

    public Object loadPropertyValue( long id )
    {
        PropertyRecord propertyRecord = getPropertyStore().getRecord( id );
        if ( propertyRecord.isLight() )
        {
            getPropertyStore().makeHeavy( propertyRecord );
        }
        return propertyRecord.getType().getValue( propertyRecord, getPropertyStore() );
    }

    public void nodeRemoveProperty( long nodeId, long propertyId )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord == null )
        {
            nodeRecord = getNodeStore().getRecord( nodeId );
        }
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on node[" +
                nodeId + "] illegal since it has been deleted." );
        }
        PropertyRecord propRecord = getPropertyRecord( propertyId );
        if ( propRecord == null )
        {
            propRecord = getPropertyStore().getRecord( propertyId );
            addPropertyRecord( propRecord );
        }
        if ( !propRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete property[" +
                propertyId + "] since it is already deleted." );
        }
        propRecord.setNodeId( nodeId );
        if ( propRecord.isLight() )
        {
            getPropertyStore().makeHeavy( propRecord );
        }

        propRecord.setInUse( false );
        // TODO: update count on property index record
        for ( DynamicRecord valueRecord : propRecord.getValueRecords() )
        {
            if ( valueRecord.inUse() )
            {
                valueRecord.setInUse( false, propRecord.getType().intValue() );
            }
        }
        long prevProp = propRecord.getPrevProp();
        long nextProp = propRecord.getNextProp();
        if ( nodeRecord.getNextProp() == propertyId )
        {
            nodeRecord.setNextProp( nextProp );
            // re-adding not a problem
            addNodeRecord( nodeRecord );
        }
        if ( prevProp != Record.NO_PREVIOUS_PROPERTY.intValue() )
        {
            PropertyRecord prevPropRecord = getPropertyRecord( prevProp );
            if ( prevPropRecord == null )
            {
                prevPropRecord = getPropertyStore().getLightRecord( prevProp );
                addPropertyRecord( prevPropRecord );
            }
            assert prevPropRecord.inUse();
            prevPropRecord.setNextProp( nextProp );
        }
        if ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord nextPropRecord = getPropertyRecord( nextProp );
            if ( nextPropRecord == null )
            {
                nextPropRecord = getPropertyStore().getLightRecord( nextProp );
                addPropertyRecord( nextPropRecord );
            }
            assert nextPropRecord.inUse();
            nextPropRecord.setPrevProp( prevProp );
        }
    }

    public PropertyData relChangeProperty( long relId, long propertyId, Object value )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId );
        if ( relRecord == null )
        {
            relRecord = getRelationshipStore().getRecord( relId );
        }
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property change on relationship[" +
                relId + "] illegal since it has been deleted." );
        }
        PropertyRecord propertyRecord = getPropertyRecord( propertyId );
        if ( propertyRecord == null )
        {
            propertyRecord = getPropertyStore().getRecord( propertyId );
            addPropertyRecord( propertyRecord );
        }
        if ( !propertyRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to change property[" +
                propertyId + "] since it is deleted." );
        }
        propertyRecord.setRelId( relId );
        if ( propertyRecord.isLight() )
        {
            getPropertyStore().makeHeavy( propertyRecord );
        }
        propertyRecord.setChanged();
        if ( propertyRecord.getType() == PropertyType.STRING )
        {
            for ( DynamicRecord record : propertyRecord.getValueRecords() )
            {
                if ( record.inUse() )
                {
                    record.setInUse( false, PropertyType.STRING.intValue() );
                }
            }
        }
        else if ( propertyRecord.getType() == PropertyType.ARRAY )
        {
            for ( DynamicRecord record : propertyRecord.getValueRecords() )
            {
                if ( record.inUse() )
                {
                    record.setInUse( false, PropertyType.ARRAY.intValue() );
                }
            }
        }
        getPropertyStore().encodeValue( propertyRecord, value );
        addPropertyRecord( propertyRecord );
        return propertyRecord.newPropertyData( value );
    }

    public PropertyData nodeChangeProperty( long nodeId, long propertyId, Object value )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord == null )
        {
            nodeRecord = getNodeStore().getRecord( nodeId );
        }
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property change on node[" +
                nodeId + "] illegal since it has been deleted." );
        }
        PropertyRecord propertyRecord = getPropertyRecord( propertyId );
        if ( propertyRecord == null )
        {
            propertyRecord = getPropertyStore().getRecord( propertyId );
            addPropertyRecord( propertyRecord );
        }
        if ( !propertyRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to change property[" +
                propertyId + "] since it is deleted." );
        }
        propertyRecord.setNodeId( nodeId );
        if ( propertyRecord.isLight() )
        {
            getPropertyStore().makeHeavy( propertyRecord );
        }
        propertyRecord.setChanged();
        if ( propertyRecord.getType() == PropertyType.STRING )
        {
            for ( DynamicRecord record : propertyRecord.getValueRecords() )
            {
                if ( record.inUse() )
                {
                    record.setInUse( false, PropertyType.STRING.intValue() );
                }
            }
        }
        else if ( propertyRecord.getType() == PropertyType.ARRAY )
        {
            for ( DynamicRecord record : propertyRecord.getValueRecords() )
            {
                if ( record.inUse() )
                {
                    record.setInUse( false, PropertyType.ARRAY.intValue() );
                }
            }
        }
        getPropertyStore().encodeValue( propertyRecord, value );
        addPropertyRecord( propertyRecord );
        return propertyRecord.newPropertyData( value );
    }

    /*
     * Public for testing only
     */
    public PropertyRecord relAddProperty( long relId, long propertyId, PropertyIndex index,
        Object value )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId );
        if ( relRecord == null )
        {
            relRecord = getRelationshipStore().getRecord( relId );
            addRelationshipRecord( relRecord );
        }
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on relationship[" +
                relId + "] illegal since it has been deleted." );
        }
        PropertyRecord propertyRecord = new PropertyRecord( propertyId );
        propertyRecord.setInUse( true );
        propertyRecord.setCreated();
        propertyRecord.setRelId( relId );
        if ( relRecord.getNextProp() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            PropertyRecord prevProp = getPropertyRecord(
                relRecord.getNextProp() );
            if ( prevProp == null )
            {
                prevProp = getPropertyStore().getLightRecord(
                    relRecord.getNextProp() );
                addPropertyRecord( prevProp );
            }
            assert prevProp.getPrevProp() ==
                Record.NO_PREVIOUS_PROPERTY.intValue();
            prevProp.setPrevProp( propertyId );
            propertyRecord.setNextProp( prevProp.getId() );
        }
        int keyIndexId = index.getKeyId();
        propertyRecord.setKeyIndexId( keyIndexId );
        getPropertyStore().encodeValue( propertyRecord, value );
        relRecord.setNextProp( propertyId );
        addPropertyRecord( propertyRecord );
        return propertyRecord;
    }

    /*
     * Public for testing only
     */
    public PropertyRecord nodeAddProperty( long nodeId, long propertyId, PropertyIndex index,
        Object value )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId );
        if ( nodeRecord == null )
        {
            nodeRecord = getNodeStore().getRecord( nodeId );
            addNodeRecord( nodeRecord );
        }
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on node[" +
                nodeId + "] illegal since it has been deleted." );
        }

        PropertyRecord propertyRecord = new PropertyRecord( propertyId );
        propertyRecord.setInUse( true );
        propertyRecord.setCreated();
        propertyRecord.setNodeId( nodeId );
        // encoding has to be set here before anything is change
        // (exception is thrown in encodeValue now and tx not marked
        // rollback only
        getPropertyStore().encodeValue( propertyRecord, value );
        if ( nodeRecord.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord prevProp = getPropertyRecord(
                nodeRecord.getNextProp() );
            if ( prevProp == null )
            {
                prevProp = getPropertyStore().getLightRecord(
                    nodeRecord.getNextProp() );
                addPropertyRecord( prevProp );
            }
            assert prevProp.getPrevProp() ==
                Record.NO_PREVIOUS_PROPERTY.intValue();
            prevProp.setPrevProp( propertyId );
            propertyRecord.setNextProp( prevProp.getId() );
        }
        int keyIndexId = index.getKeyId();
        propertyRecord.setKeyIndexId( keyIndexId );
        nodeRecord.setNextProp( propertyId );
        addPropertyRecord( propertyRecord );
        return propertyRecord;
    }

    public void relationshipCreate( long id, int type, long firstNodeId, long secondNodeId )
    {
        NodeRecord firstNode = getNodeRecord( firstNodeId );
        if ( firstNode == null )
        {
            firstNode = getNodeStore().getRecord( firstNodeId );
            addNodeRecord( firstNode );
        }
        if ( !firstNode.inUse() )
        {
            throw new IllegalStateException( "First node[" + firstNodeId +
                "] is deleted and cannot be used to create a relationship" );
        }
        NodeRecord secondNode = getNodeRecord( secondNodeId );
        if ( secondNode == null )
        {
            secondNode = getNodeStore().getRecord( secondNodeId );
            addNodeRecord( secondNode );
        }
        if ( !secondNode.inUse() )
        {
            throw new IllegalStateException( "Second node[" + secondNodeId +
                "] is deleted and cannot be used to create a relationship" );
        }
        RelationshipRecord record = new RelationshipRecord( id, firstNodeId,
            secondNodeId, type );
        record.setInUse( true );
        record.setCreated();
        addRelationshipRecord( record );
        connectRelationship( firstNode, secondNode, record );
    }

    private void connectRelationship( NodeRecord firstNode,
        NodeRecord secondNode, RelationshipRecord rel )
    {
        assert firstNode.getNextRel() != rel.getId();
        assert secondNode.getNextRel() != rel.getId();
        rel.setFirstNextRel( firstNode.getNextRel() );
        rel.setSecondNextRel( secondNode.getNextRel() );
        connect( firstNode, rel );
        connect( secondNode, rel );
        firstNode.setNextRel( rel.getId() );
        secondNode.setNextRel( rel.getId() );
    }

    private void connect( NodeRecord node, RelationshipRecord rel )
    {
        if ( node.getNextRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship( node.getNextRel() );
            getWriteLock( lockableRel );
            RelationshipRecord nextRel = getRelationshipRecord( node.getNextRel() );
            if ( nextRel == null )
            {
                nextRel = getRelationshipStore().getRecord( node.getNextRel() );
                addRelationshipRecord( nextRel );
            }
            boolean changed = false;
            if ( nextRel.getFirstNode() == node.getId() )
            {
                nextRel.setFirstPrevRel( rel.getId() );
                changed = true;
            }
            if ( nextRel.getSecondNode() == node.getId() )
            {
                nextRel.setSecondPrevRel( rel.getId() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( node + " dont match " + nextRel );
            }
        }
    }

    public void nodeCreate( long nodeId )
    {
        NodeRecord nodeRecord = new NodeRecord( nodeId );
        nodeRecord.setInUse( true );
        nodeRecord.setCreated();
        addNodeRecord( nodeRecord );
    }

    public String loadIndex( int id )
    {
        PropertyIndexStore indexStore = getPropertyStore().getIndexStore();
        PropertyIndexRecord index = getPropertyIndexRecord( id );
        if ( index == null )
        {
            index = indexStore.getRecord( id );
        }
        if ( index.isLight() )
        {
            indexStore.makeHeavy( index );
        }
        return indexStore.getStringFor( index );
    }

    public PropertyIndexData[] loadPropertyIndexes( int count )
    {
        PropertyIndexStore indexStore = getPropertyStore().getIndexStore();
        return indexStore.getPropertyIndexes( count );
    }

    public void createPropertyIndex( String key, int id )
    {
        PropertyIndexRecord record = new PropertyIndexRecord( id );
        record.setInUse( true );
        record.setCreated();
        PropertyIndexStore propIndexStore = getPropertyStore().getIndexStore();
        int keyBlockId = propIndexStore.nextKeyBlockId();
        record.setKeyBlockId( keyBlockId );
        int length = key.length();
        char[] chars = new char[length];
        key.getChars( 0, length, chars, 0 );
        Collection<DynamicRecord> keyRecords =
            propIndexStore.allocateKeyRecords( keyBlockId, chars );
        for ( DynamicRecord keyRecord : keyRecords )
        {
            record.addKeyRecord( keyRecord );
        }
        addPropertyIndexRecord( record );
    }

    public void createRelationshipType( int id, String name )
    {
        RelationshipTypeRecord record = new RelationshipTypeRecord( id );
        record.setInUse( true );
        record.setCreated();
        int blockId = (int) getRelationshipTypeStore().nextBlockId();
        record.setTypeBlock( blockId );
        int length = name.length();
        char[] chars = new char[length];
        name.getChars( 0, length, chars, 0 );
        Collection<DynamicRecord> typeNameRecords =
            getRelationshipTypeStore().allocateTypeNameRecords( blockId, chars );
        for ( DynamicRecord typeRecord : typeNameRecords )
        {
            record.addTypeRecord( typeRecord );
        }
        addRelationshipTypeRecord( record );
    }

    static class CommandSorter implements Comparator<Command>, Serializable
    {
        public int compare( Command o1, Command o2 )
        {
            long id1 = o1.getKey();
            long id2 = o2.getKey();
            long diff = id1 - id2;
            if ( diff > Integer.MAX_VALUE )
            {
                return Integer.MAX_VALUE;
            }
            else if ( diff < Integer.MIN_VALUE )
            {
                return Integer.MIN_VALUE;
            }
            else
            {
                return (int) diff;
            }
        }

        @Override
        public boolean equals( Object o )
        {
            if ( o instanceof CommandSorter )
            {
                return true;
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return 3217;
        }
    }

    void addNodeRecord( NodeRecord record )
    {
        nodeRecords.put( record.getId(), record );
    }

    NodeRecord getNodeRecord( long nodeId )
    {
        return nodeRecords.get( nodeId );
    }

    void addRelationshipRecord( RelationshipRecord record )
    {
        relRecords.put( record.getId(), record );
    }

    RelationshipRecord getRelationshipRecord( long relId )
    {
        return relRecords.get( relId );
    }

    void addPropertyRecord( PropertyRecord record )
    {
        propertyRecords.put( record.getId(), record );
    }

    PropertyRecord getPropertyRecord( long propertyId )
    {
        return propertyRecords.get( propertyId );
    }

    void addRelationshipTypeRecord( RelationshipTypeRecord record )
    {
        relTypeRecords.put( record.getId(), record );
    }

    void addPropertyIndexRecord( PropertyIndexRecord record )
    {
        propIndexRecords.put( record.getId(), record );
    }

    PropertyIndexRecord getPropertyIndexRecord( int id )
    {
        return propIndexRecords.get( id );
    }

    private static class LockableRelationship implements Relationship
    {
        private final long id;

        LockableRelationship( long id )
        {
            this.id = id;
        }

        public void delete()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Node getEndNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public long getId()
        {
            return this.id;
        }

        public GraphDatabaseService getGraphDatabase()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Node[] getNodes()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Node getOtherNode( Node node )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Object getProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Object getProperty( String key, Object defaultValue )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Iterable<String> getPropertyKeys()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Iterable<Object> getPropertyValues()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Node getStartNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public RelationshipType getType()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public boolean isType( RelationshipType type )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public boolean hasProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public Object removeProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        public void setProperty( String key, Object value )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean equals( Object o )
        {
            if ( !(o instanceof Relationship) )
            {
                return false;
            }
            return this.getId() == ((Relationship) o).getId();
        }

        @Override
        public int hashCode()
        {
            return (int) (( id >>> 32 ) ^ id );
        }

        @Override
        public String toString()
        {
            return "Lockable relationship #" + this.getId();
        }
    }

    public RelIdArray getCreatedNodes()
    {
        RelIdArray createdNodes = new RelIdArray( null );
        for ( NodeRecord record : nodeRecords.values() )
        {
            if ( record.isCreated() )
            {
                // TODO Direction doesn't matter... misuse of RelIdArray?
                createdNodes.add( record.getId(), DirectionWrapper.OUTGOING );
            }
        }
        return createdNodes;
    }

    public boolean isNodeCreated( long nodeId )
    {
        NodeRecord record = nodeRecords.get( nodeId );
        if ( record != null )
        {
            return record.isCreated();
        }
        return false;
    }

    public boolean isRelationshipCreated( long relId )
    {
        RelationshipRecord record = relRecords.get( relId );
        if ( record != null )
        {
            return record.isCreated();
        }
        return false;
    }

    public int getKeyIdForProperty( long propertyId )
    {
        PropertyRecord propRecord = getPropertyRecord( propertyId );
        if ( propRecord == null )
        {
            propRecord = getPropertyStore().getLightRecord( propertyId );
        }
        return propRecord.getKeyIndexId();
    }

    @Override
    public XAResource getXAResource()
    {
        return xaConnection.getXaResource();
    }

    @Override
    public void destroy()
    {
        xaConnection.destroy();
    }

    @Override
    public void setXaConnection( XaConnection connection )
    {
        this.xaConnection = connection;
    }

    @Override
    public PropertyData nodeAddProperty( long nodeId, PropertyIndex index, Object value )
    {
        long propertyId = neoStore.getPropertyStore().nextId();
        return nodeAddProperty( nodeId, propertyId, index, value ).newPropertyData( value );
    }

    @Override
    public PropertyData relAddProperty( long relId, PropertyIndex index, Object value )
    {
        long propertyId = neoStore.getPropertyStore().nextId();
        return relAddProperty( relId, propertyId, index, value ).newPropertyData( value );
    }

    @Override
    public RelationshipTypeData[] loadRelationshipTypes()
    {
        RelationshipTypeData relTypeData[] = neoStore.getRelationshipTypeStore().getRelationshipTypes();;
        RelationshipTypeData rawRelTypeData[] = new RelationshipTypeData[relTypeData.length];
        for ( int i = 0; i < relTypeData.length; i++ )
        {
            rawRelTypeData[i] = new RelationshipTypeData(
                relTypeData[i].getId(), relTypeData[i].getName() );
        }
        return rawRelTypeData;
    }
}
