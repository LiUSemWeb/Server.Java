package org.linkeddatafragments.datasource;


import org.rdfhdt.hdt.triples.IteratorTripleID;

import java.util.*;

import org.rdfhdt.hdt.triples.IteratorTripleID;


public class HdtIteratorLRUCache implements HdtIteratorCache {

    private final Map<String, IteratorTripleID> cache = Collections.synchronizedMap(new LinkedHashMap<String, IteratorTripleID>());
    private final Map<String, Integer> cacheCountBindings = Collections.synchronizedMap(new HashMap<String, Integer>());


    private void clear() {

        // O(1)  https://www.geeksforgeeks.org/how-to-get-first-or-last-entry-from-java-linkedhashmap/
        Set<String> cacheKeys = cache.keySet();
        String[] cacheKeysArray = cacheKeys.toArray(new String[this.size()]);

        for(int i=0; i<cacheKeysArray.length && i<NUMBEROFITEMSTOREMOVE;i++)
            this.remove(cacheKeysArray[i]);
    }

    public int size(){
        return this.cache.size();
    }

    public boolean containsKey(String key){
        return this.cache.containsKey(key) && this.cacheCountBindings.containsKey(key);
    }

    public IteratorTripleID get(String key){
        return this.cache.get(key);
    }

    public int getBindingsCount(String key){
        return this.cacheCountBindings.get(key);
    }

    public void put(String key, IteratorTripleID matches, int bindingsCount, long offset) {
        this.cache.put(key, matches);

        // this is irrelevant when bindins are not used
        this.cacheCountBindings.put(key, bindingsCount);

        if (this.cache.size() > CACHEMAXIMUMSIZE)
            this.clear();
    }

    public void remove(String key) {
        this.cache.remove(key);
        this.cacheCountBindings.remove(key);
    }

}
