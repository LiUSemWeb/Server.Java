package org.linkeddatafragments.datasource;

import org.rdfhdt.hdt.triples.IteratorTripleID;

import java.util.*;

public class HdtIteratorPagedBasedCache implements HdtIteratorCache {

    private final static long MILLISECONDSTOGOZOMBIE = 10000; // after this time an iterator is considered dead

    private final TreeMap<String, IteratorTripleID> cache = new TreeMap<>();
    private final HashMap<String, Integer> cacheCountBindings = new HashMap<>();
    private final LinkedHashMap<String, Long> cacheTimestamps = new LinkedHashMap<>(); // ordered on insertion


    private void clear() {
        long timeThreshold = System.currentTimeMillis() - MILLISECONDSTOGOZOMBIE;
        int numIteratorsRemoved = 0;

        // O(1)  https://www.geeksforgeeks.org/how-to-get-first-or-last-entry-from-java-linkedhashmap/

        // remove based on timestamp
        Set<String> cacheKeys = cacheTimestamps.keySet();
        String[] cacheKeysArray = cacheKeys.toArray(new String[this.size()]);

        int i = 0;
        while (i < NUMBEROFITEMSTOREMOVE && (cacheTimestamps.get(cacheKeysArray[i]) < timeThreshold)){
            this.remove(cacheKeysArray[i]);
            i++;
            numIteratorsRemoved++;
        }

        // remove based on page
        cacheKeys = cache.keySet();
        cacheKeysArray = cacheKeys.toArray(new String[this.size()]);
        String currentOffset = "";
        i = 0;
        while ((numIteratorsRemoved < NUMBEROFITEMSTOREMOVE) || (currentOffset.equals(cacheKeysArray[i].substring(0,20)))){
            this.remove(cacheKeysArray[i]);
            currentOffset = cacheKeysArray[i].substring(0,20);
            numIteratorsRemoved++;
            i++;
        }
    }

    @Override
    public int size(){
        return this.cache.size();
    }

    @Override
    public boolean containsKey(String key){
        return this.cache.containsKey(key);
    }

    @Override
    public IteratorTripleID get(String key){
        return this.cache.get(key);
    }

    @Override
    public int getBindingsCount(String key){
        return this.cacheCountBindings.get(key);
    }

    @Override
    public void put(String key, IteratorTripleID matches, int bindingsCount, long offset) {
        if (!this.cache.containsKey(key)){
            long unixTime = System.currentTimeMillis();

            this.cache.put(key, matches);
            this.cacheTimestamps.put(key, unixTime);

            // this is irrelevant when bindins are not used
            this.cacheCountBindings.put(key, bindingsCount);

            if (this.cache.size() > CACHEMAXIMUMSIZE)
                this.clear();
        }
    }

    @Override
    public void remove(String key) {
        if (this.cache.containsKey(key)){
            this.cache.remove(key);
            this.cacheCountBindings.remove(key);
            this.cacheTimestamps.remove(key);
        }
    }
}
