package org.linkeddatafragments.datasource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.linkeddatafragments.util.TripleElement;
import org.rdfhdt.hdt.enums.TripleComponentRole;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.hdt.HDTManager;
import org.rdfhdt.hdt.triples.IteratorTripleID;
import org.rdfhdt.hdt.triples.TripleID;
import org.rdfhdt.hdtjena.NodeDictionary;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.binding.Binding;

/**
 * An HDT data source of Basic Linked Data Fragments.
 *
 * @author Ruben Verborgh
 */
public class HdtDataSource extends DataSource
{
    private final HDT datasource;
    private final NodeDictionary dictionary;

    /**
     * Creates a new HdtDataSource.
     *
     * @param title
     *            title of the datasource
     * @param description
     *            datasource description
     * @param hdtFile
     *            the HDT datafile
     * @throws IOException
     *             if the file cannot be loaded
     */
    public HdtDataSource(String title, String description, String hdtFile)
            throws IOException
    {
        super(title, description);
        datasource = HDTManager.mapIndexedHDT(hdtFile, null);
        dictionary = new NodeDictionary(datasource.getDictionary());
    }

    @Override
    public TriplePatternFragment getFragment(TripleElement _subject,
                                             TripleElement _predicate, TripleElement _object, final long offset,
                                             final long limit, HdtIteratorCache fragmentsCache)
    {
        Resource subject = null;
        if (!_subject.name.equals("Var"))
        {
            subject = (Resource) _subject.object;
        }
        Property predicate = null;
        if (!_predicate.name.equals("Var"))
        {
            predicate = (Property) _predicate.object;
        }
        RDFNode object = null;
        if (!_object.name.equals("Var"))
        {
            object = (RDFNode) _object.object;
        }

        if (offset < 0)
        {
            throw new IndexOutOfBoundsException("offset");
        }
        if (limit < 1)
        {
            throw new IllegalArgumentException("limit");
        }

        // dataSourceCache key
        final String cacheKey = String.format("%20d", offset) + "+" + subject + "+" + predicate + "+" + object;

        boolean atOffset = true;
        final Model triples = ModelFactory.createDefaultModel();
        final IteratorTripleID matches;
        final boolean hasMatches;

        if (!fragmentsCache.containsKey(cacheKey))
        {
            // look up the result from the HDT datasource
            final int subjectId = subject == null ? 0
                    : dictionary.getIntID(subject.asNode(),
                    TripleComponentRole.SUBJECT);
            final int predicateId = predicate == null ? 0
                    : dictionary.getIntID(predicate.asNode(),
                    TripleComponentRole.PREDICATE);
            final int objectId = object == null ? 0
                    : dictionary.getIntID(object.asNode(),
                    TripleComponentRole.OBJECT);
            if (subjectId < 0 || predicateId < 0 || objectId < 0)
            {
                return new TriplePatternFragmentBase();
            }
            matches = datasource.getTriples()
                    .search(new TripleID(subjectId, predicateId, objectId));
            hasMatches = matches.hasNext();

            if (hasMatches)
            {
                // try to jump directly to the offset
                if (matches.canGoTo())
                {
                    try
                    {
                        matches.goTo(offset);
                        atOffset = true;
                    }
                    // if the offset is outside the bounds, this page has no matches
                    catch (IndexOutOfBoundsException exception)
                    {
                        atOffset = false;
                    }
                }
                // if not possible, advance to the offset iteratively
                else
                {
                    matches.goToStart();
                    for (int i = 0; !(atOffset = i == offset)
                            && matches.hasNext(); i++)
                        matches.next();
                }
            }
        }
        else
        {
            matches = fragmentsCache.get(cacheKey);
            hasMatches = matches.hasNext();
        }

        // try to add `limit` triples to the result model
        if (atOffset)
        {
            for (int i = 0; i < limit && matches.hasNext(); i++)
                triples.add(triples.asStatement(toTriple(matches.next())));
        }

        // update the cache
        fragmentsCache.remove(cacheKey);
        if (matches.hasNext() && offset > 0) // do not add to cache if it is the first page
        {
            final long newOffset = offset + limit;
            final String newCacheKey = String.format("%20d", newOffset) + "+" + subject + "+" + predicate + "+" + object;
            fragmentsCache.put(newCacheKey, matches, -1, offset);
        }



        // estimates can be wrong; ensure 0 is returned if there are no results,
        // and always more than actual results
        final long estimatedTotal = triples.size() > 0
                ? Math.max(offset + triples.size() + 1,
                matches.estimatedNumResults())
                : hasMatches ? Math.max(matches.estimatedNumResults(), 1) : 0;

        // create the fragment
        return new TriplePatternFragment()
        {
            @Override
            public Model getTriples()
            {
                return triples;
            }

            @Override
            public long getTotalSize()
            {
                return estimatedTotal;
            }
        };
    }

    /**
     * Converts the HDT triple to a Jena Triple.
     *
     * @param tripleId
     *            the HDT triple
     * @return the Jena triple
     */
    private Triple toTriple(TripleID tripleId)
    {
        return new Triple(
                dictionary.getNode(tripleId.getSubject(),
                        TripleComponentRole.SUBJECT),
                dictionary.getNode(tripleId.getPredicate(),
                        TripleComponentRole.PREDICATE),
                dictionary.getNode(tripleId.getObject(),
                        TripleComponentRole.OBJECT));
    }

    @Override
    public TriplePatternFragment getBindingFragment(
            final TripleElement _subject, final TripleElement _predicate,
            final TripleElement _object, final long offset, final long limit,
            final List<Binding> bindings, final List<Var> foundVariables, HdtIteratorCache fragmentsCache)
    {
        return getBindingFragmentByTriplePatternSubstitution(
                // return getBindingFragmentByTestingHdtMatches(
                _subject, _predicate, _object, offset, limit, bindings, foundVariables, fragmentsCache);
    }

    public TriplePatternFragment getBindingFragmentByTriplePatternSubstitution(
            final TripleElement _subject, final TripleElement _predicate,
            final TripleElement _object, final long offset, final long limit,
            final List<Binding> bindings, final List<Var> foundVariables, HdtIteratorCache fragmentsCache)
    {
        Resource subject = null;
        if (!_subject.name.equals("Var"))    { subject = (Resource) _subject.object; }
        Property predicate = null;
        if (!_predicate.name.equals("Var"))  { predicate = (Property) _predicate.object; }
        RDFNode object = null;
        if (!_object.name.equals("Var"))     { object = (RDFNode) _object.object; }

        // dataSourceCache key
        final String bindingsString = String.valueOf(bindings);
        final String cacheKey = String.format("%20d", offset) + "+" + subject + "+" + predicate + "+" + object + "+" + bindingsString;

        int triplesAddedInCurrentPage = 0;
        final Model triples = ModelFactory.createDefaultModel();
        final TripleIDCachingIterator it = new TripleIDCachingIterator(bindings, foundVariables, _subject, _predicate, _object );
        int countBindingsSoFar = 0;
        IteratorTripleID matches = null;

        if (fragmentsCache.containsKey(cacheKey)) // countBindingsSoFarCache also contains cacheKey
        {
            matches = fragmentsCache.get(cacheKey);
            countBindingsSoFar = fragmentsCache.getBindingsCount(cacheKey);
            int updateTripleIDCachingIterator = 0;  // aux var in case it is needed to update TripleIDCachingIterator

            while (triplesAddedInCurrentPage < limit && matches.hasNext())
            {
                while (triplesAddedInCurrentPage < limit && matches.hasNext())
                {
                    triples.add(triples.asStatement(toTriple(matches.next())));
                    triplesAddedInCurrentPage++;
                }
                if (triplesAddedInCurrentPage < limit && !matches.hasNext())
                {
                    while (updateTripleIDCachingIterator < countBindingsSoFar)
                    {
                        it.next();
                        updateTripleIDCachingIterator++;
                    }
                    if (it.hasNext())
                    {
                        TripleID t = it.next();
                        updateTripleIDCachingIterator++;
                        matches = datasource.getTriples().search(t);
                        countBindingsSoFar++;
                    }
                }
            }
        }
        else
        {
            int triplesCheckedSoFar = 0;
            boolean atOffset;

            while ( it.hasNext() && triplesAddedInCurrentPage < limit)
            {
                final TripleID t = it.next();
                matches = datasource.getTriples().search(t);
                final boolean hasMatches = matches.hasNext();
                if (hasMatches)
                {
                    matches.goToStart();
                    while (!(atOffset = (triplesCheckedSoFar == offset)) && matches.hasNext())
                    {
                        matches.next();
                        triplesCheckedSoFar++;
                    }
                    // try to add `limit` triples to the result model
                    if (atOffset)
                    {
                        while (triplesAddedInCurrentPage < limit  && matches.hasNext())
                        {
                            triples.add(triples.asStatement(toTriple(matches.next())));
                            triplesAddedInCurrentPage++;
                        }
                    }
                }
                countBindingsSoFar++;
            }
        }

        fragmentsCache.remove((cacheKey));
        // Put in cache only after the first page (offset > 0)
        if (matches != null && matches.hasNext() && offset > 0)
        {
            final long newOffset = offset + limit;
            final String newCacheKey = String.format("%20d", newOffset) + "+" + subject + "+" + predicate + "+" + object + "+" + bindingsString;
            fragmentsCache.put(newCacheKey, matches, countBindingsSoFar, offset);
        }


        final int bindingsSize = bindings.size();
        final long minimumTotal = offset + triplesAddedInCurrentPage + 1;
        final long estimatedTotal;
        if (triplesAddedInCurrentPage < limit)
        {
            estimatedTotal = offset + triplesAddedInCurrentPage;
        }
//         else // This else block is for testing purposes only. The next else block is the correct one.
//         {
//             estimatedTotal = minimumTotal;
//         }
        else
        {
            final int THRESHOLD = 10;
            final int maxBindingsToUseInEstimation;
            if (bindingsSize <= THRESHOLD)
            {
                maxBindingsToUseInEstimation = bindingsSize;
            } else{
                maxBindingsToUseInEstimation = THRESHOLD;
            }

            long estimationSum = 0L;
            it.reset();
            int i = 0;
            while ( it.hasNext() && i < maxBindingsToUseInEstimation )
            {
                i++;
                estimationSum += estimateResultSetSize( it.next() );
            }

            if (bindingsSize <= THRESHOLD)
            {
                if ( estimationSum <= minimumTotal )
                    estimatedTotal = minimumTotal;
                else
                    estimatedTotal = estimationSum;
            }
            else // bindingsSize > THRESHOLD
            {
                final double fraction = bindingsSize / maxBindingsToUseInEstimation;
                final double estimationAsDouble = fraction * estimationSum;
                final long estimation = Math.round(estimationAsDouble);
                if ( estimation <= minimumTotal )
                    estimatedTotal = minimumTotal;
                else
                    estimatedTotal = estimation;
            }
        }

        final long estimatedValid = estimatedTotal;

        return new TriplePatternFragment()
        {
            @Override
            public Model getTriples()
            {
                return triples;
            }

            @Override
            public long getTotalSize()
            {
                // return estimatedpageTotal;
                return estimatedValid;
            }

        };
    }

    protected long estimateResultSetSize( final TripleID t )
    {
        final IteratorTripleID matches = datasource.getTriples().search(t);

        if ( matches.hasNext() )
            return Math.max( matches.estimatedNumResults(), 1L );
        else
            return 0L;
    }

    public TriplePatternFragment getBindingFragmentByTestingHdtMatches(
            final TripleElement _subject, final TripleElement _predicate,
            final TripleElement _object, final long offset, final long limit,
            final List<Binding> bindings, final List<Var> foundVariables)
    {
        // Translate the given Jena Binding objects into Maps that map
        // variables (Jena Var objects) to the HDT identifiers of the
        // corresponding RDF terms (Jena Node objects) that the Binding
        // objects bind to the variables.
        // Doing this translation upfront is an optimization because it
        // avoids repeating the same HDT dictionary lookups over and over
        // again.
        final List<Map<Var, Integer>> solmapsWithHdtIDs = new LinkedList<Map<Var, Integer>>();
        for (Binding solmap : bindings)
        {
            final Map<Var, Integer> solmapWithHdtIDs = new HashMap<Var, Integer>();
            final Iterator<Var> it = solmap.vars();
            while (it.hasNext())
            {
                final Var var = it.next();

                int id = dictionary.getIntID(solmap.get(var),
                        TripleComponentRole.SUBJECT);

                final int idP = dictionary.getIntID(solmap.get(var),
                        TripleComponentRole.PREDICATE);
                if (idP != -1)
                {
                    if (id != -1 && id != idP)
                        throw new IllegalStateException();

                    id = idP;
                }

                final int idO = dictionary.getIntID(solmap.get(var),
                        TripleComponentRole.OBJECT);
                if (idO != -1)
                {
                    if (id != -1 && id != idO)
                        throw new IllegalStateException();

                    id = idO;
                }

                solmapWithHdtIDs.put(var, id);
            }

            solmapsWithHdtIDs.add(solmapWithHdtIDs);
        }

        return getBindingFragmentInHDT(_subject, _predicate, _object, offset,
                limit, solmapsWithHdtIDs);
    }

    public TriplePatternFragment getBindingFragmentInHDT(
            final TripleElement _subject, final TripleElement _predicate,
            final TripleElement _object, final long offset, final long limit,
            final List<Map<Var, Integer>> solmapsWithHdtIDs)
    {
        if (offset < 0)
        {
            throw new IndexOutOfBoundsException("offset");
        }
        if (limit < 1)
        {
            throw new IllegalArgumentException("limit");
        }

        // look up the result from the HDT datasource
        int subjectId = 0;
        int predicateId = 0;
        int objectId = 0;
        if (_subject.name.equals("Var"))
        {
            subjectId = 0;
            // subjectId = dictionary.getIntID(((Var) _subject.object),
            // TripleComponentRole.SUBJECT);
        }
        else
        {
            subjectId = _subject.object == null ? 0
                    : dictionary.getIntID(((Resource) _subject.object).asNode(),
                    TripleComponentRole.SUBJECT);
        }
        if (_predicate.name.equals("Var"))
        {
            // predicateId = dictionary.getIntID(((Var) _predicate.object),
            // TripleComponentRole.PREDICATE);
            predicateId = 0;
        }
        else
        {
            predicateId = _predicate == null ? 0
                    : dictionary.getIntID(
                    ((RDFNode) _predicate.object).asNode(),
                    TripleComponentRole.PREDICATE);
        }
        if (_object.name.equals("Var"))
        {
            // objectId = dictionary.getIntID(((Var) _object.object),
            // TripleComponentRole.OBJECT);
            objectId = 0;
        }
        else
        {
            objectId = _object.object == null ? 0
                    : dictionary.getIntID(((RDFNode) _object.object).asNode(),
                    TripleComponentRole.OBJECT);
        }
        if (subjectId < 0 || predicateId < 0 || objectId < 0)
        {
            return new TriplePatternFragmentBase();
        }
        final Model triples = ModelFactory.createDefaultModel();
        final IteratorTripleID matches = datasource.getTriples()
                .search(new TripleID(subjectId, predicateId, objectId));
        final boolean hasMatches = matches.hasNext();

        // prepare for repeatedly calling the isValid method
        final Var subjectVar = (_subject.name.equals("Var"))
                ? (Var) _subject.object : null;
        final Var predicateVar = (_predicate.name.equals("Var"))
                ? (Var) _predicate.object : null;
        final Var objectVar = (_object.name.equals("Var"))
                ? (Var) _object.object : null;

        int testedMatchesSoFar = 0; // everything from 'matches' that we have
        // looked at so far
        int validMatchesSoFar = 0; // the subset of 'testedMatchesSoFar' that
        // turned out to be valid for the brTPF
        int testedMatchesUntilFirstPageOfValidMatches = 0;
        if (hasMatches)
        {
            matches.goToStart();
            // iterate over the matching triples until we are at the correct
            // offset
            // (ignoring matching triples that are not compatible with any of
            // the
            // solution mappings in 'bindings')
            while (matches.hasNext())
            {
                if (validMatchesSoFar >= offset)
                {
                    break;
                }

                TripleID tripleId = matches.next();
                if (isValid(tripleId, solmapsWithHdtIDs, subjectVar,
                        predicateVar, objectVar, dictionary))
                {
                    validMatchesSoFar++;
                }

                testedMatchesSoFar++;
                if (validMatchesSoFar == limit)
                {
                    testedMatchesUntilFirstPageOfValidMatches = testedMatchesSoFar;
                }
            }

            // now we are at the correct offset; add `limit` triples to the
            // result model
            // (again, ignoring matching triples that are not compatible with
            // any of the
            // solution mappings in 'bindings')

            int validMatchesForRequestedPage = 0;
            while (validMatchesForRequestedPage < limit && matches.hasNext())
            {
                TripleID tripleId = matches.next();
                if (isValid(tripleId, solmapsWithHdtIDs, subjectVar,
                        predicateVar, objectVar, dictionary))
                {
                    triples.add(triples.asStatement(toTriple(tripleId)));
                    validMatchesSoFar++;
                    validMatchesForRequestedPage++;
                }
                testedMatchesSoFar++;
            }
        }

        // at this point it holds that: offset <= validMatchesSoFar <= offset +
        // limit
        final long estimatedValid;
        if (validMatchesSoFar == 0)
        {
            estimatedValid = 0L;
        }
        else if (validMatchesSoFar < limit)
        {
            estimatedValid = validMatchesSoFar;
        }
        else
        {
            final long estimatedMatches = matches.estimatedNumResults();
            if (testedMatchesUntilFirstPageOfValidMatches > 0)
                estimatedValid = (limit * estimatedMatches)
                        / testedMatchesUntilFirstPageOfValidMatches;
            else
                estimatedValid = (limit * estimatedMatches)
                        / testedMatchesSoFar;
        }

        // create the fragment
        return new TriplePatternFragment()
        {
            @Override
            public Model getTriples()
            {
                return triples;
            }

            @Override
            public long getTotalSize()
            {
                return estimatedValid;
            }

        };
    }

    /**
     * Assuming that tripleId is a matching triple for the triple pattern
     * (_subject,_predicate,_object), this method returns true if the solution
     * mapping that can be generated from this matching triple is compatible
     * with at least on of the solution mappings in solMapSet.
     */
    static public boolean isValid(final TripleID tripleId,
                                  final List<Map<Var, Integer>> solmapsWithHdtIDs,
                                  final Var subjectVar, final Var predicateVar, final Var objectVar,
                                  final NodeDictionary dictionary)
    {
        for (Map<Var, Integer> solMapWithHdtIDs : solmapsWithHdtIDs)
        {
            if (checkCompatibility(tripleId, solMapWithHdtIDs, subjectVar,
                    predicateVar, objectVar, dictionary))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Assuming that tripleId is a matching triple for the triple pattern
     * (_subject,_predicate,_object), this method returns true if the solution
     * mapping that can be generated from this matching triple is compatible
     * with the given solution mapping (solMap).
     */
    static public boolean checkCompatibility(final TripleID tripleId,
                                             final Map<Var, Integer> solMapWithHdtIDs, final Var subjectVar,
                                             final Var predicateVar, final Var objectVar,
                                             final NodeDictionary dictionary)
    {
        if (subjectVar != null && solMapWithHdtIDs.containsKey(subjectVar))
        {
            final int a = tripleId.getSubject();
            final int b = solMapWithHdtIDs.get(subjectVar);
            if (a != b)
            {
                return false;
            }
        }

        if (predicateVar != null && solMapWithHdtIDs.containsKey(predicateVar))
        {
            final int a = tripleId.getPredicate();
            final int b = solMapWithHdtIDs.get(predicateVar);
            if (a != b)
            {
                return false;
            }
        }

        if (objectVar != null && solMapWithHdtIDs.containsKey(objectVar))
        {
            final int a = tripleId.getObject();
            final int b = solMapWithHdtIDs.get(objectVar);
            if (a != b)
            {
                return false;
            }
        }

        return true;
    }

    protected class TripleIDProducingIterator implements Iterator<TripleID>
    {
        protected final boolean sIsVar, pIsVar, oIsVar;
        protected final boolean canHaveMatches;

        protected final List<Node> sBindings = new ArrayList<Node> ();
        protected final List<List<Node>> pBindings = new ArrayList<List<Node>> ();
        protected final List<List<List<Node>>> oBindings = new ArrayList<List<List<Node>>> ();

        protected int curSubjID, curPredID, curObjID;
        protected int curSubjIdx, curPredIdx, curObjIdx;
        protected List<Node> curPredBindings;
        protected List<Node> curObjBindings;
        protected boolean ready;

        public TripleIDProducingIterator( final List<Binding> jenaSolMaps,
                                          final List<Var> foundVariables,
                                          final TripleElement subjectOfTP,
                                          final TripleElement predicateOfTP,
                                          final TripleElement objectOfTP )
        {
            int numOfVarsCoveredBySolMaps = 0;

            if ( subjectOfTP.var != null )
            {
                this.sIsVar = true;
                if ( foundVariables.contains(subjectOfTP.var) )
                    numOfVarsCoveredBySolMaps++;
            }
            else
                this.sIsVar = false;

            if ( predicateOfTP.var != null )
            {
                this.pIsVar = true;
                if ( foundVariables.contains(predicateOfTP.var) )
                    numOfVarsCoveredBySolMaps++;
            }
            else
                this.pIsVar = false;

            if ( objectOfTP.var != null )
            {
                this.oIsVar = true;
                if ( foundVariables.contains(objectOfTP.var) )
                    numOfVarsCoveredBySolMaps++;
            }
            else
                this.oIsVar = false;

            final boolean needToCheckForDuplicates = ( numOfVarsCoveredBySolMaps < foundVariables.size() );

            curSubjID = ( subjectOfTP.node == null ) ?
                    0 :
                    dictionary.getIntID( subjectOfTP.node,
                            TripleComponentRole.SUBJECT );

            curPredID = ( predicateOfTP.node == null ) ?
                    0 :
                    dictionary.getIntID( predicateOfTP.node,
                            TripleComponentRole.PREDICATE );

            curObjID = ( objectOfTP.node == null ) ?
                    0 :
                    dictionary.getIntID( objectOfTP.node,
                            TripleComponentRole.OBJECT );

            canHaveMatches = ( curSubjID >= 0 ) && ( curPredID >= 0 ) && ( curObjID >= 0 );

            if ( canHaveMatches )
            {
                for ( Binding solMap : jenaSolMaps )
                {
                    final Node s = sIsVar ? solMap.get(subjectOfTP.var) : null;
                    final Node p = pIsVar ? solMap.get(predicateOfTP.var) : null;
                    final Node o = oIsVar ? solMap.get(objectOfTP.var) :null;

                    final List<Node> pBindingsForS;
                    final List<List<Node>> oBindingsForS;
                    int sIdx;
                    if (    ! needToCheckForDuplicates
                            || (sIdx = sBindings.indexOf(s)) == -1 )
                    {
                        sBindings.add( s );

                        pBindingsForS = new ArrayList<Node> ();
                        pBindings.add( pBindingsForS );

                        oBindingsForS = new ArrayList<List<Node>> ();
                        oBindings.add( oBindingsForS );
                    }
                    else
                    {
                        pBindingsForS = pBindings.get( sIdx );
                        oBindingsForS = oBindings.get( sIdx );
                    }

                    final List<Node> oBindingsForSP;
                    int pIdx;
                    if (    ! needToCheckForDuplicates
                            || (pIdx = pBindingsForS.indexOf(p)) == -1 )
                    {
                        pBindingsForS.add( p );

                        oBindingsForSP = new ArrayList<Node> ();
                        oBindingsForS.add( oBindingsForSP );
                    }
                    else
                    {
                        oBindingsForSP = oBindingsForS.get( pIdx );
                    }

                    int oIdx;
                    if (    ! needToCheckForDuplicates
                            || (pIdx = oBindingsForSP.indexOf(o)) == -1 )
                    {
                        oBindingsForSP.add( o );
                    }
                }
            }

            reset();
        }

        @Override
        public boolean hasNext()
        {
            if ( ready )
                return true;

            if ( ! canHaveMatches )
                return false;

            do {
                int prevSubjIdx = curSubjIdx;
                int prevPredIdx = curPredIdx;

                curObjIdx++;

                while ( curPredID == -1 || curObjIdx >= curObjBindings.size() )
                {
                    curPredIdx++;
                    while ( curSubjID == -1 || curPredIdx >= curPredBindings.size() )
                    {
                        curSubjIdx++;
                        if ( curSubjIdx >= sBindings.size() )
                        {
                            return false;
                        }

                        curPredBindings = pBindings.get( curSubjIdx );
                        curPredIdx = 0;

                        if ( sIsVar )
                            curSubjID = dictionary.getIntID( sBindings.get(curSubjIdx),
                                    TripleComponentRole.SUBJECT );
                    }

                    curObjBindings = oBindings.get( curSubjIdx ).get( curPredIdx );
                    curObjIdx = 0;

                    if ( pIsVar )
                        curPredID = dictionary.getIntID( curPredBindings.get(curPredIdx),
                                TripleComponentRole.PREDICATE );
                }

                if ( oIsVar )
                    curObjID = dictionary.getIntID( curObjBindings.get(curObjIdx),
                            TripleComponentRole.OBJECT );
            }
            while ( curSubjID == -1 || curPredID == -1 || curObjID == -1 );

            ready = true;
            return true;
        }

        @Override
        public TripleID next()
        {
            if ( ! hasNext() )
                throw new NoSuchElementException();

            ready = false;
            return new TripleID( curSubjID, curPredID, curObjID );
        }

        public void reset()
        {
            ready = canHaveMatches;

            if ( canHaveMatches )
            {
                curSubjIdx = curPredIdx = curObjIdx = 0;

                curPredBindings = pBindings.get( curSubjIdx );
                curObjBindings = oBindings.get( curSubjIdx ).get( curPredIdx );

                if ( sIsVar )
                    curSubjID = dictionary.getIntID( sBindings.get(curSubjIdx),
                            TripleComponentRole.SUBJECT );

                if ( pIsVar )
                    curPredID = dictionary.getIntID( curPredBindings.get(curPredIdx),
                            TripleComponentRole.PREDICATE );

                if ( oIsVar )
                    curObjID = dictionary.getIntID( curObjBindings.get(curObjIdx),
                            TripleComponentRole.OBJECT );
            }
        }

    } // end of TripleIDProducingIterator

    public class TripleIDCachingIterator implements Iterator<TripleID>
    {
        protected final TripleIDProducingIterator it;
        protected final List<TripleID> cache = new ArrayList<TripleID> ();

        protected boolean replayMode = false;
        protected int replayIdx = 0;

        public TripleIDCachingIterator( final List<Binding> jenaSolMaps,
                                        final List<Var> foundVariables,
                                        final TripleElement subjectOfTP,
                                        final TripleElement predicateOfTP,
                                        final TripleElement objectOfTP )
        {
            it = new TripleIDProducingIterator( jenaSolMaps,
                    foundVariables,
                    subjectOfTP,
                    predicateOfTP,
                    objectOfTP );
        }

        @Override
        public boolean hasNext()
        {
            if ( replayMode && replayIdx < cache.size() )
                return true;

            replayMode = false;
            return it.hasNext();
        }

        @Override
        public TripleID next()
        {
            if ( ! hasNext() )
                throw new NoSuchElementException();

            if ( replayMode ) {
                return cache.get( replayIdx++ );
            }

            final TripleID t = it.next();
            cache.add( t );
            return t;
        }

        public void reset()
        {
            replayMode = true;
            replayIdx = 0;
        }

    } // end of TripleIDCachingIterator

}