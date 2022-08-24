package org.linkeddatafragments.datasource;


import org.rdfhdt.hdt.triples.IteratorTripleID;


public interface HdtIteratorCache {

    final static long CACHEMAXIMUMSIZE = 1000; // in terms of number of iterators
    final static long NUMBEROFITEMSTOREMOVE = 200; // number of iterators to remove from cache when it gets full

    int size();

    boolean containsKey(String key);

    IteratorTripleID get(String key);

    int getBindingsCount(String key);

    void put(String key, IteratorTripleID matches, int bindingsCount, long offset);

    void remove(String key);

}
