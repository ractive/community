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
package org.neo4j.server.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.neo4j.server.WebTestUtils.CLIENT;

import java.io.IOException;
import java.util.Collections;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.neo4j.server.NeoServerWithEmbeddedWebServer;
import org.neo4j.server.ServerBuilder;
import org.neo4j.server.database.DatabaseBlockedException;
import org.neo4j.server.rest.domain.GraphDbHelper;
import org.neo4j.server.rest.domain.JsonHelper;
import org.neo4j.server.rest.domain.JsonParseException;
import org.neo4j.server.rest.domain.RelationshipDirection;
import org.neo4j.server.rest.repr.formats.CompactJsonFormat;

import com.sun.jersey.api.client.ClientResponse;

public class CompactJsonFunctionalTest {
    private long thomasAnderson;
    private long trinity;
    private long thomasAndersonLovesTrinity;

    private NeoServerWithEmbeddedWebServer server;
    private FunctionalTestHelper functionalTestHelper;
    private GraphDbHelper helper;

    @Before
    public void setupServer() throws IOException {
        server = ServerBuilder.server().withRandomDatabaseDir().withPassingStartupHealthcheck().build();
        server.start();
        functionalTestHelper = new FunctionalTestHelper(server);
        helper = functionalTestHelper.getGraphDbHelper();

        // Create the matrix example
        thomasAnderson = createAndIndexNode("Thomas Anderson");
        trinity = createAndIndexNode("Trinity");
        long tank = createAndIndexNode("Tank");

        long knowsRelationshipId = helper.createRelationship( "KNOWS", thomasAnderson, trinity );
        thomasAndersonLovesTrinity = helper.createRelationship("LOVES", thomasAnderson, trinity);
        helper.setRelationshipProperties( thomasAndersonLovesTrinity, Collections.singletonMap( "strength", (Object) 100 ) );
        helper.createRelationship("KNOWS", thomasAnderson, tank);
        helper.createRelationship("KNOWS", trinity, tank);

        // index a relationship
        helper.createRelationshipIndex( "relationships" );
        helper.addRelationshipToIndex( "relationships", "key", "value", knowsRelationshipId );

        // index a relationship
        helper.createRelationshipIndex( "relationships2" );
        helper.addRelationshipToIndex( "relationships2", "key2", "value2", knowsRelationshipId );

    }

    @After
    public void stopServer() {
        server.stop();
    }

    private long createAndIndexNode(String name) throws DatabaseBlockedException {
        long id = helper.createNode();
        helper.setNodeProperties(id, Collections.singletonMap("name", (Object) name));
        helper.addNodeToIndex("node", "name", name, id);
        return id;
    }

    @Test
    @Ignore
    public void shouldGetRoot() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.dataUri()).accept(CompactJsonFormat.MEDIA_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertValidJson(response.getEntity(String.class));
        response.close();
    }

    @Test
    @Ignore
    public void shouldGetNodeIndexRoot() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.nodeIndexUri()).accept(MediaType.TEXT_HTML_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertValidJson(response.getEntity(String.class));
        response.close();
    }

    @Test
    @Ignore
    public void shouldGetRelationshipIndexRoot() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.relationshipIndexUri()).accept(MediaType.TEXT_HTML_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertValidJson(response.getEntity(String.class));
        response.close();
    }

    @Test
    @Ignore
    public void shouldGetTrinityWhenSearchingForHer() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.indexNodeUri("node", "name", "Trinity" )).accept(MediaType.TEXT_HTML_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String entity = response.getEntity(String.class);
        assertTrue(entity.contains("Trinity"));
        assertValidJson(entity);
        response.close();
    }

    @Test
    public void shouldGetThomasAndersonDirectly() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.nodeUri(thomasAnderson)).accept(CompactJsonFormat.MEDIA_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String entity = response.getEntity(String.class);
        assertTrue(entity.contains("Thomas Anderson"));
        assertValidJson(entity);
        response.close();
    }

    @Test
    @Ignore
    public void shouldGetSomeRelationships() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.relationshipsUri(thomasAnderson, RelationshipDirection.all.name(), "KNOWS")).accept(
                MediaType.TEXT_HTML_TYPE).get(ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String entity = response.getEntity(String.class);
        assertTrue(entity.contains("KNOWS"));
        assertFalse(entity.contains("LOVES"));
        assertValidJson(entity);
        response.close();

        response = CLIENT.resource(functionalTestHelper.relationshipsUri(thomasAnderson, RelationshipDirection.all.name(), "LOVES")).accept(MediaType.TEXT_HTML_TYPE).get(
                ClientResponse.class);
        entity = response.getEntity(String.class);
        assertFalse(entity.contains("KNOWS"));
        assertTrue(entity.contains("LOVES"));
        assertValidJson(entity);
        response.close();

        response = CLIENT.resource(functionalTestHelper.relationshipsUri(thomasAnderson, RelationshipDirection.all.name(), "LOVES", "KNOWS")).accept(
                MediaType.TEXT_HTML_TYPE).get(ClientResponse.class);
        entity = response.getEntity(String.class);
        assertTrue(entity.contains("KNOWS"));
        assertTrue(entity.contains("LOVES"));
        assertValidJson(entity);
        response.close();
    }

    @Test
    @Ignore
    public void shouldGetThomasAndersonLovesTrinityRelationship() {
        ClientResponse response = CLIENT.resource(functionalTestHelper.relationshipUri(thomasAndersonLovesTrinity)).accept(MediaType.TEXT_HTML_TYPE).get(
                ClientResponse.class);
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String entity = response.getEntity(String.class);
        assertTrue(entity.contains("strength"));
        assertTrue(entity.contains("100"));
        assertTrue(entity.contains("LOVES"));
        assertValidJson(entity);
        response.close();
    }
 
    private void assertValidJson(String entity) {
        System.out.println(entity);
        try
        {
            assertTrue(JsonHelper.jsonToMap( entity ).containsKey( "self" ));
            assertFalse(JsonHelper.jsonToMap( entity ).containsKey( "properties" ));
        }
        catch ( JsonParseException e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
