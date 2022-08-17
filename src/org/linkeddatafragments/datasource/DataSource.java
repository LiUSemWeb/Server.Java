package org.linkeddatafragments.datasource;

import org.linkeddatafragments.util.TripleElement;

/**
 *
 * @author mielvandersande
 */
public abstract class DataSource implements IDataSource {
    protected String title;
    protected String description;

    public DataSource(String title, String description) {
        this.title = title;
        this.description = description;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    ;

    @Override
    public String getTitle() {
        return this.title;
    }

    ;

    @Override
    public void close() {
    }

    public abstract TriplePatternFragment getFragment(TripleElement _subject,
                                                      TripleElement _predicate, TripleElement _object, long offset,
                                                      long limit, HdtIteratorCache fragmentsCache);

}
