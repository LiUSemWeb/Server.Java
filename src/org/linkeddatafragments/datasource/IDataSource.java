package org.linkeddatafragments.datasource;

import java.io.Closeable;
import java.util.List;

import org.linkeddatafragments.util.TripleElement;

import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.binding.Binding;

/**
 * A data source of Basic Linked Data Fragments.
 * @author Ruben Verborgh
 */
public interface IDataSource extends Closeable {
	/**
	 * Gets a page of the Basic Linked Data Fragment matching the specified triple pattern.
	 *
	 * @param subject        the subject (null to match any subject)
	 * @param predicate      the predicate (null to match any predicate)
	 * @param object         the object (null to match any object)
	 * @param offset         the triple index at which to start the page
	 * @param limit          the number of triples on the page
	 * @param fragmentsCache
	 * @return the first page of the fragment
	 */
	public TriplePatternFragment getFragment(TripleElement subject, TripleElement predicate, TripleElement object,
											 long offset, long limit, HdtIteratorCache fragmentsCache);
        public String getTitle();
        
        public String getDescription();
        
        public TriplePatternFragment getBindingFragment(TripleElement subject, TripleElement predicate, TripleElement object,
                                                        long offset, long limit, List<Binding> bindings, List<Var> foundVariables, HdtIteratorCache fragmentsCache);
}
