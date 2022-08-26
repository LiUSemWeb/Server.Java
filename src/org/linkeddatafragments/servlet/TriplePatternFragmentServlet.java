package org.linkeddatafragments.servlet;

import static org.linkeddatafragments.util.CommonResources.HYDRA_COLLECTION;
import static org.linkeddatafragments.util.CommonResources.HYDRA_FIRSTPAGE;
import static org.linkeddatafragments.util.CommonResources.HYDRA_ITEMSPERPAGE;
import static org.linkeddatafragments.util.CommonResources.HYDRA_MAPPING;
import static org.linkeddatafragments.util.CommonResources.HYDRA_NEXTPAGE;
import static org.linkeddatafragments.util.CommonResources.HYDRA_PAGEDCOLLECTION;
import static org.linkeddatafragments.util.CommonResources.HYDRA_PREVIOUSPAGE;
import static org.linkeddatafragments.util.CommonResources.HYDRA_PROPERTY;
import static org.linkeddatafragments.util.CommonResources.HYDRA_SEARCH;
import static org.linkeddatafragments.util.CommonResources.HYDRA_TEMPLATE;
import static org.linkeddatafragments.util.CommonResources.HYDRA_TOTALITEMS;
import static org.linkeddatafragments.util.CommonResources.HYDRA_VARIABLE;
import static org.linkeddatafragments.util.CommonResources.INVALID_URI;
import static org.linkeddatafragments.util.CommonResources.RDF_OBJECT;
import static org.linkeddatafragments.util.CommonResources.RDF_PREDICATE;
import static org.linkeddatafragments.util.CommonResources.RDF_SUBJECT;
import static org.linkeddatafragments.util.CommonResources.RDF_TYPE;
import static org.linkeddatafragments.util.CommonResources.VOID_DATASET;
import static org.linkeddatafragments.util.CommonResources.VOID_SUBSET;
import static org.linkeddatafragments.util.CommonResources.VOID_TRIPLES;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.utils.URIBuilder;
import org.linkeddatafragments.config.ConfigReader;
import org.linkeddatafragments.datasource.*;
import org.linkeddatafragments.util.TripleElement;

import com.google.gson.JsonObject;
import com.hp.hpl.jena.datatypes.TypeMapper;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.shared.InvalidPropertyURIException;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.binding.Binding;
import com.hp.hpl.jena.sparql.expr.E_LogicalOr;
import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.syntax.Element;
import com.hp.hpl.jena.sparql.syntax.ElementFilter;
import com.hp.hpl.jena.sparql.syntax.ElementGroup;

/**
 * Servlet that responds with a Basic Linked Data Fragment.
 *
 * @author Ruben Verborgh
 */
public class TriplePatternFragmentServlet extends HttpServlet
{

    private final static long serialVersionUID = 1L;
    private final static Pattern STRINGPATTERN = Pattern
            .compile("^\"(.*)\"(?:@(.*)|\\^\\^<?([^<>]*)>?)?$");
    private final static TypeMapper types = TypeMapper.getInstance();
    private final static long TRIPLESPERPAGE = 100;

    private ConfigReader config;
    private final HashMap<String, IDataSource> dataSources = new HashMap<>();

    //private final static HdtIteratorCache fragmentsCache = new HdtIteratorPagedBasedCache();
    private final static HdtIteratorCache fragmentsCache = new HdtIteratorLRUCache();

    @Override
    public void init(ServletConfig servletConfig) throws ServletException
    {
        try
        {
            // find the configuration file
            String applicationPathStr = servletConfig.getServletContext()
                    .getRealPath("/");
            if (applicationPathStr == null)
            { // this can happen when running standalone
                applicationPathStr = System.getProperty("user.dir");
            }
            final File applicationPath = new File(applicationPathStr);

            File configFile = new File(applicationPath, "config-example.json");
            if (servletConfig.getInitParameter("configFile") != null)
            {
                configFile = new File(
                        servletConfig.getInitParameter("configFile"));
            }

            if (!configFile.exists())
            {
                throw new Exception("Configuration file " + configFile
                        + " not found.");
            }

            if (!configFile.isFile())
            {
                throw new Exception("Configuration file " + configFile
                        + " is not a file.");
            }

            // load the configuration
            config = new ConfigReader(new FileReader(configFile));
            for (Entry<String, JsonObject> dataSource : config.getDataSources()
                    .entrySet())
            {
                dataSources.put(dataSource.getKey(),
                        DataSourceFactory.create(dataSource.getValue()));
            }
        }
        catch (Exception e)
        {
            throw new ServletException( e.getMessage(), e );
        }
    }

    @Override
    public void destroy()
    {
        for ( IDataSource dataSource : dataSources.values() ) {
            try {
                dataSource.close();
            }
            catch( Exception e ) {
                // ignore
            }
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException
    {
//if ( request.getQueryString() == null ) {
//System.out.println( request.getRequestURI() );
//} else {
        //System.out.println( request.getRequestURI() + "?" + request.getQueryString() );
//}
        // possible inputs: s: ?var, p: blank node, o: literal/URI
        try
        {
            // find the data source
            final String contextPath = request.getContextPath();
            final String requestURI = request.getRequestURI();
            final String path = contextPath == null ? requestURI : requestURI
                    .substring(contextPath.length());
            final String query = request.getQueryString();
            final String dataSourceName = path.substring(1);
            final IDataSource dataSource = dataSources.get(dataSourceName);
            if (dataSource == null)
            {
                throw new Exception(
                        "Data source not found (" + dataSourceName + ", contextPath: " + contextPath + ", requestURI: " + requestURI + ")." );
            }

            // query the fragment
            String subjectString = request.getParameter("subject");
            String predicateString = request.getParameter("predicate");
            String objectString = request.getParameter("object");

            // final Resource subject = parseAsResource(subjectString);
            // final Property predicate = parseAsProperty(predicateString);
            // final RDFNode object = parseAsNode(objectString);
            final TripleElement subject = parseAsResource(subjectString);
            final TripleElement predicate = parseAsProperty(predicateString);
            final TripleElement object = parseAsNode(objectString);

            final long page = Math.max(1,
                    parseAsInteger(request.getParameter("page")));
            final long limit = TRIPLESPERPAGE, offset = limit * (page - 1);

            // Olaf and Carlos work
            // **************
            final List<Var> foundVariables = new ArrayList<Var> ();
            final List<Binding> bindings = parseAsSetOfBindings(
                    request.getParameter("values"),
                    foundVariables );

            TriplePatternFragment _fragment = null;
            if (bindings != null)
                _fragment = dataSource.getBindingFragment(subject, predicate,  object, offset, limit, bindings, foundVariables, fragmentsCache);
            else
                _fragment = dataSource.getFragment(subject, predicate, object, offset, limit, fragmentsCache);


            final TriplePatternFragment fragment = _fragment;

            // fill the output model
            final Model output = fragment.getTriples();
            output.setNsPrefixes(config.getPrefixes());


            // add dataset metadata
            final String hostName = request.getHeader("Host");
            final String datasetUrl = request.getScheme() + "://"
                    + (hostName == null ? request.getServerName() : hostName)
                    + request.getRequestURI();
            final String fragmentUrl = query == null ? datasetUrl : (datasetUrl
                    + "?" + query);
            final Resource datasetId = output.createResource(datasetUrl
                    + "#dataset");
            final Resource fragmentId = output.createResource(fragmentUrl);
            output.add(datasetId, RDF_TYPE, VOID_DATASET);
            output.add(datasetId, RDF_TYPE, HYDRA_COLLECTION);
            output.add(datasetId, VOID_SUBSET, fragmentId);

            // add fragment metadata
            output.add(fragmentId, RDF_TYPE, HYDRA_COLLECTION);
            output.add(fragmentId, RDF_TYPE, HYDRA_PAGEDCOLLECTION);
            final Literal total = output.createTypedLiteral(
                    fragment.getTotalSize(), XSDDatatype.XSDinteger);
            output.add(fragmentId, VOID_TRIPLES, total);
            output.add(fragmentId, HYDRA_TOTALITEMS, total);
            output.add(fragmentId, HYDRA_ITEMSPERPAGE,
                    output.createTypedLiteral(limit, XSDDatatype.XSDinteger));

            // add pages
            final URIBuilder pagedUrl = new URIBuilder(fragmentUrl);
            pagedUrl.setParameter("page", "1");
            output.add(fragmentId, HYDRA_FIRSTPAGE,
                    output.createResource(pagedUrl.toString()));
            if (offset > 0)
            {
                pagedUrl.setParameter("page", Long.toString(page - 1));

                output.add(fragmentId, HYDRA_PREVIOUSPAGE,
                        output.createResource(pagedUrl.toString()));
            }
            if (offset + limit < fragment.getTotalSize())
            {
                pagedUrl.setParameter("page", Long.toString(page + 1));
                output.add(fragmentId, HYDRA_NEXTPAGE,
                        output.createResource(pagedUrl.toString()));
            }

            // add controls
            final Resource triplePattern = output.createResource();
            final Resource subjectMapping = output.createResource();
            final Resource predicateMapping = output.createResource();
            final Resource objectMapping = output.createResource();
            output.add(datasetId, HYDRA_SEARCH, triplePattern);
            output.add(
                    triplePattern,
                    HYDRA_TEMPLATE,
                    output.createLiteral(datasetUrl
                            + "{?subject,predicate,object}"));
            output.add(triplePattern, HYDRA_MAPPING, subjectMapping);
            output.add(triplePattern, HYDRA_MAPPING, predicateMapping);
            output.add(triplePattern, HYDRA_MAPPING, objectMapping);
            output.add(subjectMapping, HYDRA_VARIABLE,
                    output.createLiteral("subject"));
            output.add(subjectMapping, HYDRA_PROPERTY, RDF_SUBJECT);
            output.add(predicateMapping, HYDRA_VARIABLE,
                    output.createLiteral("predicate"));
            output.add(predicateMapping, HYDRA_PROPERTY, RDF_PREDICATE);
            output.add(objectMapping, HYDRA_VARIABLE,
                    output.createLiteral("object"));
            output.add(objectMapping, HYDRA_PROPERTY, RDF_OBJECT);

            // serialize the output as Turtle
            response.setHeader("Server", "Linked Data Fragments Server");
            response.setContentType("text/turtle");
            response.setCharacterEncoding("utf-8");
            output.write(response.getWriter(), "Turtle", fragmentUrl);
        }
        catch (Exception e)
        {
            System.err.println( "Exception caught: " + e.getMessage() );
            e.printStackTrace( System.err );
            throw new ServletException( e.getMessage(), e );
        }
    }

    /**
     * Parses the given value as set of bindings.
     *
     * @param value containing the SPARQL bindings
     * @param foundVariables a list with variables found in the VALUES clause
     *
     * @return a list with solution mappings found in the VALUES clause
     */
    public static List<Binding> parseAsSetOfBindings( final String value,
                                                      final List<Var> foundVariables )
    {
        if (value == null)
        {
            return null;
        }
        String newString = "select * where {} VALUES " + value;
        Query q = QueryFactory.create(newString);
        foundVariables.addAll( q.getValuesVariables() );

        return q.getValuesData();
    }

    /**
     * Parses the given value as a set of FILTER operators.
     *
     * @param value
     *            with a string containing FILTER expressions
     * @return the parsed FILTER expressions in a single FILTER
     */
    public static Expr parseAsSetOfFilters(String value)
    {
        if (value == null)
        {
            return null;
        }
        Expr eor = null;
        Query q = QueryFactory.create("select * where { " + value + " }");
        ElementGroup qBody = (ElementGroup) q.getQueryPattern();
        for (Element e : qBody.getElements())
        {
            if (e instanceof ElementFilter)
            {
                ElementFilter ef = (ElementFilter) e;
                if (eor == null)
                {
                    eor = ef.getExpr();
                }
                else
                {
                    eor = new E_LogicalOr(eor, ef.getExpr());
                }
            }
        }
        return eor;
    }

    /**
     * Parses the given value as an integer.
     *
     * @param value
     *            the value
     * @return the parsed value
     */
    int parseAsInteger(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex)
        {
            return 0;
        }
    }

    /**
     * Parses the given value as an RDF resource.
     *
     * @param value
     *            the value
     * @return the parsed value, or null if unspecified
     */
    public static TripleElement parseAsResource(String value)
    {
        final TripleElement subject = parseAsNode(value);
        if (subject.object == null)
        {
            return new TripleElement("null", null);
        }
        if (subject.name.equals("Var"))
        {
            return subject;
        }
        return subject.object == null || subject.object instanceof Resource ? new TripleElement(
                "RDFNode", (Resource) subject.object) : new TripleElement(
                "Property", INVALID_URI);
    }

    /**
     * Parses the given value as an RDF property.
     *
     * @param value
     *            the value
     * @return the parsed value, or null if unspecified
     */
    public static TripleElement parseAsProperty(String value)
    {
        // final RDFNode predicateNode = parseAsNode(value);
        final TripleElement predicateNode = parseAsNode(value);
        if (predicateNode.object == null)
        {
            return new TripleElement("null", null);
        }
        if (predicateNode.name.equals("Var"))
        {
            return predicateNode;
        }
        if (predicateNode.object instanceof Resource)
        {
            try
            {
                return new TripleElement(
                        "Property",
                        ResourceFactory
                                .createProperty(((Resource) predicateNode.object)
                                        .getURI()));
            }
            catch (InvalidPropertyURIException ex)
            {
                return new TripleElement("Property", INVALID_URI);
            }
        }
        return predicateNode.object == null ? null : new TripleElement(
                "Property", INVALID_URI);
    }

    /**
     * Parses the given value as an RDF node.
     *
     * @param value
     *            the value
     * @return the parsed value, or null if unspecified
     */
    public static TripleElement parseAsNode(String value)
    {
        // nothing or empty indicates an unknown
        if (value == null || value.length() == 0)
        {
            return new TripleElement("null", null);
        }
        // find the kind of entity based on the first character
        final char firstChar = value.charAt(0);
        switch (firstChar)
        {
            // variable or blank node indicates an unknown
            case '?':
                return new TripleElement(Var.alloc(value.replaceAll("\\?","")));
            case '_':
                return null;
            // angular brackets indicate a URI
            case '<':
                return new TripleElement(
                        ResourceFactory.createResource(value.substring(1,
                                value.length() - 1)));
            // quotes indicate a string
            case '"':
                final Matcher matcher = STRINGPATTERN.matcher(value);
                if (matcher.matches())
                {
                    final String body = matcher.group(1);
                    final String lang = matcher.group(2);
                    final String type = matcher.group(3);
                    if (lang != null)
                    {
                        return new TripleElement(
                                ResourceFactory.createLangLiteral(body, lang));
                    }
                    if (type != null)
                    {
                        return new TripleElement(
                                ResourceFactory.createTypedLiteral(body,
                                        types.getSafeTypeByName(type)));
                    }
                    return new TripleElement(
                            ResourceFactory.createPlainLiteral(body));
                }
                return new TripleElement("Property", INVALID_URI);
            // assume it's a URI without angular brackets
            default:
                return new TripleElement(
                        ResourceFactory.createResource(value));
        }
    }
}
