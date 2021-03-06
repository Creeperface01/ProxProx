package io.gomint.proxprox.util;

import io.gomint.jraknet.PacketBuffer;
import io.gomint.proxprox.debug.Debugger;
import io.gomint.proxprox.network.protocol.PacketInventoryTransaction;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author geNAZt
 * @version 1.0
 */
@RequiredArgsConstructor
public class EntityRewriter {

    private static final Logger LOGGER = LoggerFactory.getLogger( EntityRewriter.class );

    @Setter private Debugger debugger;
    @Getter @Setter private long ownId;
    @Getter @Setter private long currentDownStreamId;
    private AtomicLong idCounter = new AtomicLong( 0 );

    private Map<Long, Long> rewriteIds = new ConcurrentHashMap<>();
    private Map<Long, Long> serverRewriteIds = new ConcurrentHashMap<>();

    public long addEntity( String from, long entityID ) {
        long newEntityId = this.idCounter.incrementAndGet();
        if ( newEntityId == this.ownId ) {
            newEntityId = this.idCounter.incrementAndGet();
        }

        this.debugger.addEntity( from, entityID, newEntityId );

        this.serverRewriteIds.put( newEntityId, entityID );
        this.rewriteIds.put( entityID, newEntityId );
        return newEntityId;
    }

    public PacketBuffer rewriteServerToClient( String from, byte packetId, int pos, PacketBuffer buffer ) {
        // Entity ID rewrites
        long entityId;
        switch ( packetId ) {
            case 0x4a:  // Boss event
                entityId = buffer.readSignedVarInt();
                long replacementID = getReplacementId( entityId );

                if ( entityId != replacementID ) {
                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    buffer = new PacketBuffer( 8 );
                    buffer.writeSignedVarLong( replacementID );
                    buffer.writeBytes( data );
                    buffer.resetPosition();


                    this.debugger.addEntityRewrite( from, "UpStream", packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x28:  // Entity motion
            case 0x1f:  // Mob equip
            case 0x20:  // Mob Armor
            case 0x12:  // Entity move
            case 0x13:  // Move player
            case 0x1B:  // Entity event
            case 0x27:  // Entity metadata
            case 0x1D:  // Update attributes
            case 0x1C:  // Mob effect
                entityId = buffer.readUnsignedVarLong();
                replacementID = getReplacementId( entityId );

                if ( entityId != replacementID ) {
                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    buffer = new PacketBuffer( 8 );
                    buffer.writeUnsignedVarLong( replacementID );
                    buffer.writeBytes( data );
                    buffer.resetPosition();


                    this.debugger.addEntityRewrite( from, "UpStream", packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x2C:  // Animation
                int actionId = buffer.readSignedVarInt();

                entityId = buffer.readUnsignedVarLong();
                replacementID = getReplacementId( entityId );

                if ( entityId != replacementID ) {
                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    buffer = new PacketBuffer( 8 );
                    buffer.writeSignedVarInt( actionId );
                    buffer.writeUnsignedVarLong( replacementID );
                    buffer.writeBytes( data );
                    buffer.resetPosition();

                    this.debugger.addEntityRewrite( from, "UpStream", packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x11: // Pickup entity
                long itemId = buffer.readUnsignedVarLong();
                long playerId = buffer.readUnsignedVarLong();
                long replaceItemId = getReplacementId( itemId );
                long replacePlayerId = getReplacementId( playerId );

                buffer = new PacketBuffer( 8 );
                buffer.writeUnsignedVarLong( replaceItemId );
                buffer.writeUnsignedVarLong( replacePlayerId );
                buffer.resetPosition();

                this.debugger.addEntityRewrite( from, "UpStream", packetId, itemId, replaceItemId );
                this.debugger.addEntityRewrite( from, "UpStream", packetId, playerId, replacePlayerId );

                break;

            case 0x37:  // Adventure settings
                int a = buffer.readUnsignedVarInt();
                int b = buffer.readUnsignedVarInt();
                int c = buffer.readUnsignedVarInt();
                int d = buffer.readUnsignedVarInt();
                int e = buffer.readUnsignedVarInt();
                entityId = buffer.readLLong(); // Yes, a LE long
                replacementID = getReplacementId( entityId );

                buffer = new PacketBuffer( 8 );
                buffer.writeUnsignedVarInt( a );
                buffer.writeUnsignedVarInt( b );
                buffer.writeUnsignedVarInt( c );
                buffer.writeUnsignedVarInt( d );
                buffer.writeUnsignedVarInt( e );
                buffer.writeLLong( replacementID );
                buffer.resetPosition();

                break;
        }

        return buffer;
    }

    public long getReplacementId( long entityId ) {
        if ( entityId == this.currentDownStreamId ) {
            return this.ownId;
        }

        Long rewrite = this.rewriteIds.get( entityId );
        if ( rewrite == null ) {
            LOGGER.warn( "Got entity packet for entity not spawned yet: " + entityId );
            return entityId;
        }

        return rewrite;
    }

    private long getReplacementIdForServer( long entityId ) {
        if ( entityId == this.ownId ) {
            return this.currentDownStreamId;
        }

        Long rewriteId = this.serverRewriteIds.get( entityId );
        if ( rewriteId == null ) {
            LOGGER.error( "Did not find replacement id for " + entityId );

            for ( Map.Entry<Long, Long> longLongEntry : this.serverRewriteIds.entrySet() ) {
                LOGGER.info( "Found " + longLongEntry.getKey() + " -> " + longLongEntry.getValue() );
            }

            return entityId;
        }

        return rewriteId;
    }

    public PacketBuffer rewriteClientToServer( String to, byte packetId, int pos, PacketBuffer buffer ) {
        long entityId;

        switch ( packetId ) {
            case 0x4a:  // Boss event
                entityId = buffer.readSignedVarInt();
                long replacementID = getReplacementId( entityId );

                if ( entityId != replacementID ) {
                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    buffer = new PacketBuffer( 8 );
                    buffer.writeSignedVarLong( replacementID );
                    buffer.writeBytes( data );
                    buffer.resetPosition();


                    this.debugger.addEntityRewrite( "UpStream", to, packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x1f:
            case 0x20:
            case 0x13: // Move player
            case 0x27: // Entity metadata
            case 0x1B: // Entity Event
            case 0x24: // Player action
                entityId = buffer.readUnsignedVarLong();
                replacementID = getReplacementIdForServer( entityId );

                if ( entityId != replacementID ) {
                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    buffer = new PacketBuffer( 8 );
                    buffer.writeUnsignedVarLong( replacementID );
                    buffer.writeBytes( data );
                    buffer.resetPosition();

                    this.debugger.addEntityRewrite( "UpStream", to, packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x2c:  // Animate
                int actionId = buffer.readSignedVarInt();
                entityId = buffer.readUnsignedVarLong();
                replacementID = getReplacementIdForServer( entityId );

                if ( entityId != replacementID ) {
                    buffer = new PacketBuffer( 6 );
                    buffer.writeSignedVarInt( actionId );
                    buffer.writeUnsignedVarLong( replacementID );
                    buffer.resetPosition();

                    this.debugger.addEntityRewrite( "UpStream", to, packetId, entityId, replacementID );
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x21:  // Interact
                byte action = buffer.readByte();
                entityId = buffer.readUnsignedVarLong();

                // Special case id 0 (own reference)
                if ( entityId != 0 ) {
                    replacementID = getReplacementIdForServer( entityId );

                    byte[] data = new byte[buffer.getRemaining()];
                    buffer.readBytes( data );

                    if ( entityId != replacementID ) {
                        buffer = new PacketBuffer( 6 );
                        buffer.writeByte( action );
                        buffer.writeUnsignedVarLong( replacementID );
                        buffer.writeBytes( data );
                        buffer.resetPosition();

                        this.debugger.addEntityRewrite( "UpStream", to, packetId, entityId, replacementID );
                    } else {
                        buffer.setPosition( pos );
                    }
                } else {
                    buffer.setPosition( pos );
                }

                break;

            case 0x1E: // Inventory transaction
                PacketInventoryTransaction inventoryTransaction = new PacketInventoryTransaction();
                inventoryTransaction.deserialize( buffer );

                // Check if the action is a entity based
                if ( inventoryTransaction.getType() == PacketInventoryTransaction.TYPE_USE_ITEM_ON_ENTITY ) {
                    entityId = inventoryTransaction.getEntityId();
                    inventoryTransaction.setEntityId( getReplacementIdForServer( entityId ) );
                    buffer = new PacketBuffer( 8 );
                    inventoryTransaction.serialize( buffer );
                    buffer.resetPosition();

                    this.debugger.addEntityRewrite( "UpStream", to, packetId, entityId, inventoryTransaction.getEntityId() );
                } else {
                    buffer.setPosition( pos );
                }

                break;
        }

        return buffer;
    }

    public long removeEntity( String from, long entityId ) {
        this.debugger.removeEntity( from, entityId );

        Long newEntity = this.rewriteIds.remove( entityId );
        if ( newEntity == null ) {
            return entityId;
        }

        this.serverRewriteIds.remove( newEntity );
        return newEntity;
    }

    public void removeServerEntity( long entityId ) {
        this.debugger.removeEntity( "UpStream", entityId );

        Long oldId = this.serverRewriteIds.remove( entityId );
        if ( oldId != null ) {
            this.rewriteIds.remove( oldId );
        }
    }

}
