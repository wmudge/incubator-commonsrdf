/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.rdf.api;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Test Dataset implementation
 * <p>
 * To add to your implementation's tests, create a subclass with a name ending
 * in <code>Test</code> and provide {@link #createFactory()} which minimally
 * must support {@link RDF#createDataset()} and {@link RDF#createIRI(String)}, but
 * ideally support all operations.
 * <p>
 * This test uses try-with-resources blocks for calls to {@link Dataset#stream()}
 * and {@link Dataset#iterate()}.
 * 
 * @see Dataset
 * @see RDF
 */
public abstract class AbstractDatasetTest {

    protected RDF factory;
    protected Dataset dataset;
    protected IRI alice;
    protected IRI bob;
    protected IRI name;
    protected IRI knows;
    protected IRI member;
    protected BlankNode bnode1;
    protected BlankNode bnode2;
    protected Literal aliceName;
    protected Literal bobName;
    protected Literal secretClubName;
    protected Literal companyName;
    protected Quad bobNameQuad;
    private IRI isPrimaryTopicOf;
    private IRI graph1;
    private BlankNode graph2;

    /**
     * 
     * This method must be overridden by the implementing test to provide a
     * factory for the test to create {@link Dataset}, {@link IRI} etc.
     * 
     * @return {@link RDF} instance to be tested.
     */
    protected abstract RDF createFactory();

    @Before
    public void createDatasetAndAdd() {
        factory = createFactory();
        dataset = factory.createDataset();
        assertEquals(0, dataset.size());

        graph1 = factory.createIRI("http://example.com/graph1");
        graph2 = factory.createBlankNode();
        
        alice = factory.createIRI("http://example.com/alice");
        bob = factory.createIRI("http://example.com/bob");
        name = factory.createIRI("http://xmlns.com/foaf/0.1/name");
        knows = factory.createIRI("http://xmlns.com/foaf/0.1/knows");
        member = factory.createIRI("http://xmlns.com/foaf/0.1/member");
        bnode1 = factory.createBlankNode("org1");
        bnode2 = factory.createBlankNode("org2");

        secretClubName = factory.createLiteral("The Secret Club");
        companyName = factory.createLiteral("A company");
        aliceName = factory.createLiteral("Alice");
        bobName = factory.createLiteral("Bob", "en-US");

        dataset.add(graph1, alice, name, aliceName);
        dataset.add(graph1, alice, knows, bob);

        dataset.add(graph1, alice, member, bnode1);

        bobNameQuad = factory.createQuad(graph2, bob, name, bobName);
        dataset.add(bobNameQuad);

        dataset.add(factory.createQuad(graph2, bob, member, bnode1));
        dataset.add(factory.createQuad(graph2, bob, member, bnode2));
        // NOTE: bnode1 used in both graph1 and graph2
        dataset.add(graph1, bnode1, name, secretClubName);
        dataset.add(graph2, bnode2, name, companyName);
        
        // default graph describes graph1 and graph2        
        isPrimaryTopicOf = factory.createIRI("http://xmlns.com/foaf/0.1/isPrimaryTopicOf");
        dataset.add(null, alice, isPrimaryTopicOf, graph1);
        dataset.add(null, bob, isPrimaryTopicOf, graph2);
        
        
    }

    @Test
    public void size() throws Exception {
        assertEquals(10, dataset.size());
    }

    @Test
    public void iterate() throws Exception {
        Assume.assumeTrue(dataset.size() > 0);
        List<Quad> quads = new ArrayList<>();
        for (Quad t : dataset.iterate()) {
            quads.add(t);
        }
        assertEquals(dataset.size(), quads.size());
        assertTrue(quads.contains(bobNameQuad));

        // aborted iteration
        Iterable<Quad> iterate = dataset.iterate();
        Iterator<Quad> it = iterate.iterator();

        assertTrue(it.hasNext());
        it.next();
        closeIterable(iterate);

        // second iteration - should start from fresh and
        // get the same count
        long count = 0;
        Iterable<Quad> iterable = dataset.iterate();
        for (@SuppressWarnings("unused")
        Quad t : iterable) {
            count++;
        }
        assertEquals(dataset.size(), count);
    }

    /**
     * Special quad closing for RDF4J.
     */
    private void closeIterable(Iterable<Quad> iterate) throws Exception {
        if (iterate instanceof AutoCloseable) {
            ((AutoCloseable) iterate).close();
        }
    }

    @Test
    public void iterateFilter() throws Exception {
        List<RDFTerm> friends = new ArrayList<>();
        IRI alice = factory.createIRI("http://example.com/alice");
        IRI knows = factory.createIRI("http://xmlns.com/foaf/0.1/knows");
        for (Quad t : dataset.iterate(null, alice, knows, null)) {
            friends.add(t.getObject());
        }
        assertEquals(1, friends.size());
        assertEquals(bob, friends.get(0));

        // .. can we iterate over zero hits?
        Iterable<Quad> iterate = dataset.iterate(Optional.of(graph2), bob, knows, alice);
        for (Quad unexpected : iterate) {
            fail("Unexpected quad " + unexpected);
        }
        // closeIterable(iterate);
    }

    @Test
    public void contains() throws Exception {
        assertFalse(dataset.contains(null, bob, knows, alice)); // or so he claims..

        assertTrue(dataset.contains(Optional.of(graph1), alice, knows, bob));

        try (Stream<? extends Quad> stream = dataset.stream()) {
            Optional<? extends Quad> first = stream.skip(4).findFirst();
            Assume.assumeTrue(first.isPresent());
            Quad existingQuad = first.get();
            assertTrue(dataset.contains(existingQuad));
        }

        Quad nonExistingQuad = factory.createQuad(graph2, bob, knows, alice);
        assertFalse(dataset.contains(nonExistingQuad));

        // An existing quad
        Quad quad = factory.createQuad(graph1, alice, knows, bob);
        // FIXME: Should not this always be true?
         assertTrue(dataset.contains(quad));
    }

    @Test
    public void remove() throws Exception {
        long fullSize = dataset.size();
        dataset.remove(Optional.of(graph1), alice, knows, bob);
        long shrunkSize = dataset.size();
        assertEquals(1, fullSize - shrunkSize);

        dataset.remove(Optional.of(graph1), alice, knows, bob);
        assertEquals(shrunkSize, dataset.size()); // unchanged

        dataset.add(graph1, alice, knows, bob);
        dataset.add(graph2, alice, knows, bob);
        dataset.add(graph2, alice, knows, bob);
        // Undetermined size at this point -- but at least it
        // should be bigger
        assertTrue(dataset.size() > shrunkSize);

        // and after a single remove they should all be gone
        dataset.remove(null, alice, knows, bob);
        assertEquals(shrunkSize, dataset.size());

        Quad otherQuad;
        try (Stream<? extends Quad> stream = dataset.stream()) {
            Optional<? extends Quad> anyQuad = stream.findAny();
            Assume.assumeTrue(anyQuad.isPresent());
            otherQuad = anyQuad.get();
        }

        dataset.remove(otherQuad);
        assertEquals(shrunkSize - 1, dataset.size());
        dataset.remove(otherQuad);
        assertEquals(shrunkSize - 1, dataset.size()); // no change

        // for some reason in rdf4j this causes duplicates!
        dataset.add(otherQuad);
        // dataset.stream().forEach(System.out::println);
        // should have increased
        assertTrue(dataset.size() >= shrunkSize);
    }

    @Test
    public void clear() throws Exception {
        dataset.clear();
        assertFalse(dataset.contains(null, alice, knows, bob));
        assertEquals(0, dataset.size());
        dataset.clear(); // no-op
        assertEquals(0, dataset.size());
        assertFalse(dataset.contains(null, null, null, null)); // nothing here
    }

    @Test
    public void getQuads() throws Exception {
        long quadCount;
        try (Stream<? extends Quad> stream = dataset.stream()) {
            quadCount = stream.count();
        }
        assertTrue(quadCount > 0);

        try (Stream<? extends Quad> stream = dataset.stream()) {
            assertTrue(stream.allMatch(t -> dataset.contains(t)));
        }

        // Check exact count
        Assume.assumeNotNull(bnode1, bnode2, aliceName, bobName, secretClubName, companyName, bobNameQuad);
        assertEquals(10, quadCount);
    }

    @Test
    public void getQuadsQuery() throws Exception {

        try (Stream<? extends Quad> stream = dataset.stream(Optional.of(graph1), alice, null, null)) {
            long aliceCount = stream.count();
            assertTrue(aliceCount > 0);
            Assume.assumeNotNull(aliceName);
            assertEquals(3, aliceCount);
        }

        Assume.assumeNotNull(bnode1, bnode2, bobName, companyName, secretClubName);
        try (Stream<? extends Quad> stream = dataset.stream(null, null, name, null)) {
            assertEquals(4, stream.count());
        }
        Assume.assumeNotNull(bnode1);
        try (Stream<? extends Quad> stream = dataset.stream(null, null, member, null)) {
            assertEquals(3, stream.count());
        }
    }

    @Test
    public void addBlankNodesFromMultipleDatasets() {
            // Create two separate Dataset instances
            Dataset g1 = createDataset1();
            Dataset g2 = createDataset2();

            // and add them to a new Dataset g3
            Dataset g3 = factory.createDataset();
            addAllQuads(g1, g3);
            addAllQuads(g2, g3);

            // Let's make a map to find all those blank nodes after insertion
            // (The Dataset implementation is not currently required to
            // keep supporting those BlankNodes with contains() - see
            // COMMONSRDF-15)

            final Map<String, BlankNodeOrIRI> whoIsWho = new ConcurrentHashMap<>();
            // ConcurrentHashMap as we will try parallel forEach below,
            // which should not give inconsistent results (it does with a
            // HashMap!)

            // look up BlankNodes by name
            IRI name = factory.createIRI("http://xmlns.com/foaf/0.1/name");
            try (Stream<? extends Quad> stream = g3.stream(null, null, name, null)) {
                stream.parallel().forEach(t -> whoIsWho.put(t.getObject().ntriplesString(), t.getSubject()));
            }

            assertEquals(4, whoIsWho.size());
            // and contains 4 unique values
            assertEquals(4, new HashSet<BlankNodeOrIRI>(whoIsWho.values()).size());

            BlankNodeOrIRI b1Alice = whoIsWho.get("\"Alice\"");
            assertNotNull(b1Alice);
            BlankNodeOrIRI b2Bob = whoIsWho.get("\"Bob\"");
            assertNotNull(b2Bob);
            BlankNodeOrIRI b1Charlie = whoIsWho.get("\"Charlie\"");
            assertNotNull(b1Charlie);
            BlankNodeOrIRI b2Dave = whoIsWho.get("\"Dave\"");
            assertNotNull(b2Dave);

            // All blank nodes should differ
            notEquals(b1Alice, b2Bob);
            notEquals(b1Alice, b1Charlie);
            notEquals(b1Alice, b2Dave);
            notEquals(b2Bob, b1Charlie);
            notEquals(b2Bob, b2Dave);
            notEquals(b1Charlie, b2Dave);

            // And we should be able to query with them again
            // as we got them back from g3
            IRI hasChild = factory.createIRI("http://example.com/hasChild");
            // FIXME: Check graph2 BlankNode in these ..?
            assertTrue(g3.contains(null, b1Alice, hasChild, b2Bob));
            assertTrue(g3.contains(null, b2Dave, hasChild, b1Charlie));
            // But not
            assertFalse(g3.contains(null, b1Alice, hasChild, b1Alice));
            assertFalse(g3.contains(null, b1Alice, hasChild, b1Charlie));
            assertFalse(g3.contains(null, b1Alice, hasChild, b2Dave));
            // nor
            assertFalse(g3.contains(null, b2Dave, hasChild, b1Alice));
            assertFalse(g3.contains(null, b2Dave, hasChild, b1Alice));

            // and these don't have any children (as far as we know)
            assertFalse(g3.contains(null, b2Bob, hasChild, null));
            assertFalse(g3.contains(null, b1Charlie, hasChild, null));
    }

    private void notEquals(BlankNodeOrIRI node1, BlankNodeOrIRI node2) {
        assertFalse(node1.equals(node2));
        // in which case we should be able to assume
        // (as they are in the same dataset)
        assertFalse(node1.ntriplesString().equals(node2.ntriplesString()));
    }

    /**
     * Add all quads from the source to the target.
     * <p>
     * The quads may be copied in any order. No special conversion or
     * adaptation of {@link BlankNode}s are performed.
     *
     * @param source
     *            Source Dataset to copy quads from
     * @param target
     *            Target Dataset where quads will be added
     */
    private void addAllQuads(Dataset source, Dataset target) {

        // unordered() as we don't need to preserve quad order
        // sequential() as we don't (currently) require target Dataset to be
        // thread-safe

        try (Stream<? extends Quad> stream = source.stream()) {
            stream.unordered().sequential().forEach(t -> target.add(t));
        }
    }

    /**
     * Make a new dataset with two BlankNodes - each with a different
     * uniqueReference
     */
    private Dataset createDataset1() {
        RDF factory1 = createFactory();

        IRI name = factory1.createIRI("http://xmlns.com/foaf/0.1/name");
        Dataset g1 = factory1.createDataset();
        BlankNode b1 = createOwnBlankNode("b1", "0240eaaa-d33e-4fc0-a4f1-169d6ced3680");
        g1.add(b1, b1, name, factory1.createLiteral("Alice"));

        BlankNode b2 = createOwnBlankNode("b2", "9de7db45-0ce7-4b0f-a1ce-c9680ffcfd9f");
        g1.add(b2, b2, name, factory1.createLiteral("Bob"));

        IRI hasChild = factory1.createIRI("http://example.com/hasChild");
        g1.add(null, b1, hasChild, b2);

        return g1;
    }

    /**
     * Create a different implementation of BlankNode to be tested with
     * dataset.add(a,b,c); (the implementation may or may not then choose to
     * translate such to its own instances)
     * 
     * @param name
     * @return
     */
    private BlankNode createOwnBlankNode(String name, String uuid) {
        return new BlankNode() {
            @Override
            public String ntriplesString() {                
                return "_: " + name;
            }

            @Override
            public String uniqueReference() {
                return uuid;
            }

            @Override
            public int hashCode() {
                return uuid.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof BlankNode)) {
                    return false;
                }
                BlankNode other = (BlankNode) obj;
                return uuid.equals(other.uniqueReference());
            }
        };
    }

    private Dataset createDataset2() {
        RDF factory2 = createFactory();
        IRI name = factory2.createIRI("http://xmlns.com/foaf/0.1/name");

        Dataset g2 = factory2.createDataset();

        BlankNode b1 = createOwnBlankNode("b1", "bc8d3e45-a08f-421d-85b3-c25b373abf87");
        g2.add(b1, b1, name, factory2.createLiteral("Charlie"));

        BlankNode b2 = createOwnBlankNode("b2", "2209097a-5078-4b03-801a-6a2d2f50d739");
        g2.add(b2, b2, name, factory2.createLiteral("Dave"));

        IRI hasChild = factory2.createIRI("http://example.com/hasChild");
        // NOTE: Opposite direction of loadDataset1
        g2.add(b2, b2, hasChild, b1);
        return g2;
    }
    
    /**
     * Ensure {@link Dataset#getGraphNames()} contains our two graphs.
     * 
     * @throws Exception
     *             If test fails
     */
    @Test
    public void getGraphNames() throws Exception {
        Set<BlankNodeOrIRI> names = dataset.getGraphNames().collect(Collectors.toSet());        
        assertTrue("Can't find graph name " + graph1, names.contains(graph1));
        assertTrue("Can't find graph name " + graph2, names.contains(graph2));

        // Some implementations like Virtuoso might have additional internal graphs,
        // so we can't assume this:
        //assertEquals(2, names.size());
    }
    
    @Test
    public void getGraph() throws Exception {
        Graph defaultGraph = dataset.getGraph();
        // TODO: Can we assume the default graph was empty before our new triples?
        assertEquals(2, defaultGraph.size());
        assertTrue(defaultGraph.contains(alice, isPrimaryTopicOf, graph1));
        // NOTE: graph2 is a BlankNode
        assertTrue(defaultGraph.contains(bob, isPrimaryTopicOf, graph2));
    }


    @Test
    public void getGraphNull() throws Exception {
        // Default graph should be present
        Graph defaultGraph = dataset.getGraph(null).get();
        // TODO: Can we assume the default graph was empty before our new triples?
        assertEquals(2, defaultGraph.size());
        assertTrue(defaultGraph.contains(alice, isPrimaryTopicOf, graph1));
        // NOTE: graph2 is a BlankNode
        assertTrue(defaultGraph.contains(bob, isPrimaryTopicOf, graph2));
    }
    

    @Test
    public void getGraph1() throws Exception {
        // graph1 should be present
        Graph g1 = dataset.getGraph(graph1).get();
        assertEquals(4, g1.size());
        
        assertTrue(g1.contains(alice, name, aliceName));
        assertTrue(g1.contains(alice, knows, bob));
        assertTrue(g1.contains(alice, member, bnode1));
        assertTrue(g1.contains(bnode1, name, secretClubName));
    }

    @Test
    public void getGraph2() throws Exception {
        // graph2 should be present, even if is named by a BlankNode
        Graph g2 = dataset.getGraph(graph2).get();
        assertEquals(4, g2.size());
        Triple bobNameTriple = bobNameQuad.asTriple();
        assertTrue(g2.contains(bobNameTriple));
        assertTrue(g2.contains(bob, member, bnode1));
        assertTrue(g2.contains(bob, member, bnode2));
        assertFalse(g2.contains(bnode1, name, secretClubName));
        assertTrue(g2.contains(bnode2, name, companyName));
    }
    

    
    /**
     * An attempt to use the Java 8 streams to look up a more complicated query.
     * <p>
     * FYI, the equivalent SPARQL version (untested):
     * 
     * <pre>
     *     SELECT ?orgName WHERE {
     *             ?org foaf:name ?orgName .
     *             ?alice foaf:member ?org .
     *             ?bob foaf:member ?org .
     *             ?alice foaf:knows ?bob .
     *           FILTER NOT EXIST { ?bob foaf:knows ?alice }
     *    }
     * </pre>
     *
     * @throws Exception If test fails
     */
    @Test
    public void whyJavaStreamsMightNotTakeOverFromSparql() throws Exception {
        Assume.assumeNotNull(bnode1, bnode2, secretClubName);
        // Find a secret organizations
        try (Stream<? extends Quad> stream = dataset.stream(null, null, knows, null)) {
            assertEquals("\"The Secret Club\"",
                    // Find One-way "knows"
                    stream.filter(t -> !dataset.contains(null, (BlankNodeOrIRI) t.getObject(), knows, t.getSubject()))
                            .map(knowsQuad -> {
                                try (Stream<? extends Quad> memberOf = dataset
                                        // and those they know, what are they
                                        // member of?
                                        .stream(null, (BlankNodeOrIRI) knowsQuad.getObject(), member, null)) {
                                    return memberOf
                                            // keep those which first-guy is a
                                            // member of
                                            .filter(memberQuad -> dataset.contains(null, knowsQuad.getSubject(), member,
                                                    // First hit is good enough
                                                    memberQuad.getObject()))
                                            .findFirst().get().getObject();
                                }
                            })
                            // then look up the name of that org
                            .map(org -> {
                                try (Stream<? extends Quad> orgName = dataset.stream(null, (BlankNodeOrIRI) org, name,
                                        null)) {
                                    return orgName.findFirst().get().getObject().ntriplesString();
                                }
                            }).findFirst().get());
        }
    }
}