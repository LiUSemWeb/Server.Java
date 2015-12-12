package org.linkeddatafragments.exceptions;

/**
 *
 * @author mielvandersande
 */
public class DataSourceException extends Exception {

    public DataSourceException(Throwable cause) {
        super(cause.getMessage(), cause);
    }

    public DataSourceException(String message) {
        super("Could not create DataSource: " + message);
    }
    
}
