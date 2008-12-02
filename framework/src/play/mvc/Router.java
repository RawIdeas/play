package play.mvc;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jregex.Matcher;
import jregex.Pattern;
import play.Logger;
import play.Play;
import play.Play.Mode;
import play.exceptions.EmptyAppException;
import play.vfs.VirtualFile;
import play.exceptions.NoRouteFoundException;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;

/**
 * The router matches HTTP requests to action invocations
 */
public class Router {

    static Pattern routePattern = new Pattern("^({method}[A-Za-z\\*]+)?\\s+({path}/[^\\s]*)\\s+({action}[^\\s(]+)({params}.+)?$");
    /**
     * Pattern used to locate a method override instruction in request.querystring
     */
    static Pattern methodOverride = new Pattern("^.*x-http-method-override=({method}GET|PUT|POST|DELETE).*$");
    static long lastLoading;
    static boolean empty = true;

    public static void load() {
        empty = true;
        routes.clear();
        String config = "";
        for (VirtualFile file : Play.routes) {
            config += file.contentAsString() + "\n";
        }
        String[] lines = config.split("\n");
        for (String line : lines) {
            line = line.trim().replaceAll("\\s+", " ");
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = routePattern.matcher(line);
            if (matcher.matches()) {
                Route route = new Route();
                route.method = matcher.group("method");
                route.path = matcher.group("path");
                route.action = matcher.group("action");
                route.addParams(matcher.group("params"));
                route.compute();
                routes.add(route);
            } else {
                Logger.warn("Invalid route definition : %s", line);
            }
        }
        lastLoading = System.currentTimeMillis();
    }

    public static void detectChanges() {
    	if (Play.mode==Mode.PROD)
    		return;
        for (VirtualFile file : Play.routes) {
            if (file.lastModified() > lastLoading) {
                load();
                return;
            }
        }
    }
    static List<Route> routes = new ArrayList<Route>();

    public static void route(Http.Request request) {
        if (empty) {
            throw new EmptyAppException();
        }
        // request method may be overriden if a x-http-method-override parameter is given
        if( request.querystring != null && methodOverride.matches(request.querystring)) {
            Matcher matcher = methodOverride.matcher(request.querystring);
            if (matcher.matches()) {
                Logger.info("request method %s overriden to %s ", request.method, matcher.group("method") );
                request.method = matcher.group("method");
            }            
        }
        for (Route route : routes) {
            Map<String, String> args = route.matches(request.method, request.path);
            if (args != null) {
                request.routeArgs = args;
                request.action = route.action;
                return;
            }
        }
        throw new NotFound(request.method, request.path);
    }

    public static Map<String, String> route(String method, String path) {
        if (routes.isEmpty()) {
            throw new EmptyAppException();
        }
        for (Route route : routes) {
            Map<String, String> args = route.matches(method, path);
            if (args != null) {
                args.put("action", route.action);
                return args;
            }
        }
        return new HashMap<String, String>();
    }

    public static ActionDefinition reverse(String action) {
        return reverse(action, new HashMap<String, Object>());
    }

    public static ActionDefinition reverseForTemplate(String action, Map<String, Object> r) {
        ActionDefinition actionDef = reverse(action, r);
        if ( !("GET".equals(actionDef.method) || "POST".equals(actionDef.method))) {
            String separator = actionDef.url.indexOf('?') != -1 ? "&" : "?";
            actionDef.url += separator +"x-http-method-override=" + actionDef.method;
        }
        return actionDef;
    }

    public static String getFullUrl(String action, Map<String, Object> args) {
        return Http.Request.current().getBase() + reverse(action, args);
    }

    public static String getFullUrl(String action) {
        return getFullUrl(action, new HashMap<String, Object>());
    }

    public static ActionDefinition reverse(String action, Map<String, Object> args) {
        if (action.startsWith("controllers.")) {
            action = action.substring(12);
        }
        for (Route route : routes) {
            if (route.action.equals(action)) {
                List<String> inPathArgs = new ArrayList<String>();
                boolean allRequiredArgsAreHere = true;
                // les noms de parametres matchent ils ?
                for (Route.Arg arg : route.args) {
                    inPathArgs.add(arg.name);
                    Object value = args.get(arg.name);
                    if (value==null) {
                    	allRequiredArgsAreHere = false;
                        break;
                    } else {
	                	if (value instanceof List)
	                		value = ((List<Object>) value).get(0);
	                	if (!arg.constraint.matches((String)value)) {
	                		allRequiredArgsAreHere = false;
	                        break;
	                	}
                    }
                }
                // les parametres codes en dur dans la route matchent-ils ?
                for (String staticKey : route.staticArgs.keySet()) {
                    if (!args.containsKey(staticKey) || !args.get(staticKey).equals(route.staticArgs.get(staticKey))) {
                        allRequiredArgsAreHere = false;
                        break;
                    }
                }
                if (allRequiredArgsAreHere) {
                    StringBuilder queryString = new StringBuilder();
                    String path = route.path;
                    for (String key : args.keySet()) {
                        if (inPathArgs.contains(key) && args.get(key) != null) {
                        	if (List.class.isAssignableFrom(args.get(key).getClass())) {
                        		List<Object> vals = (List<Object>) args.get(key);
                        		path = path.replaceAll("\\{(<[^>]+>)?" + key + "\\}", vals.get(0) + "");
                        	} else 
                        		path = path.replaceAll("\\{(<[^>]+>)?" + key + "\\}", args.get(key) + "");
                        } else if (route.staticArgs.containsKey(key)) {
                            // Do nothing -> The key is static
                        } else if (args.get(key) != null) {
							if (List.class.isAssignableFrom(args.get(key).getClass())) {
								List<Object> vals = (List<Object>) args.get(key);
								for (Object object : vals) {
									try {
										queryString.append(URLEncoder.encode(key, "utf-8"));
										queryString.append("=");
										queryString.append(URLEncoder.encode(object.toString() + "", "utf-8"));
										queryString.append("&");
									} catch (UnsupportedEncodingException ex) {}
								}
							} else {
	                            try {
	                                queryString.append(URLEncoder.encode(key, "utf-8"));
	                                queryString.append("=");
	                                queryString.append(URLEncoder.encode(args.get(key) + "", "utf-8"));
	                                queryString.append("&");
	                            } catch (UnsupportedEncodingException ex) {}
                        	}
                        }
                    }
                    String qs = queryString.toString();
                    if (qs.endsWith("&")) {
                        qs = qs.substring(0, qs.length() - 1);
                    }
                    ActionDefinition actionDefinition = new ActionDefinition();
                    actionDefinition.url = qs.length() == 0 ? path : path + "?" + qs;
                    actionDefinition.method = route.method == null || route.method.equals("*") ? "GET" : route.method.toUpperCase();
                    return actionDefinition;
                }
            }
        }
        throw new NoRouteFoundException(action, args);
    }

    public static class ActionDefinition {

        public String method;
        public String url;

        @Override
        public String toString() {
            return url;
        }
    }

    static class Route {

        String method;
        String path;
        String action;
        String staticDir;
        Pattern pattern;
        List<Arg> args = new ArrayList<Arg>();
        Map<String, String> staticArgs = new HashMap<String, String>();
        static Pattern customRegexPattern = new Pattern("\\{([a-zA-Z_0-9]+)\\}");
        static Pattern argsPattern = new Pattern("\\{<([^>]+)>([a-zA-Z_0-9]+)\\}");
        static Pattern paramPattern = new Pattern("([a-zA-Z_0-9]+):'(.*)'");

        public void compute() {
        	// staticDir
        	if(action.startsWith("staticDir:")) {
        		if(!method.equalsIgnoreCase("*") && !method.equalsIgnoreCase("GET")) {
        			Logger.warn("Static route only support GET method");
        			return;
        		}
        		if(!this.path.endsWith("/") && !this.path.equals("/")) {
        			this.path += "/"; 
        		}
        		this.pattern = new Pattern(path+"({resource}.*)");
        		this.staticDir = action.substring("staticDir:".length());
        	} else {        	
	            String patternString = path;
	            patternString = customRegexPattern.replacer("\\{<[^/]+>$1\\}").replace(patternString);
	            Matcher matcher = argsPattern.matcher(patternString);
	            while (matcher.find()) {
	                Arg arg = new Arg();
	                arg.name = matcher.group(2);
	                arg.constraint = new Pattern(matcher.group(1));
	                args.add(arg);
	            }
	            patternString = argsPattern.replacer("({$2}$1)").replace(patternString);
	            this.pattern = new Pattern(patternString);
                    Router.empty = false;
        	}
        }

        public void addParams(String params) {
            if (params == null) {
                return;
            }
            params = params.substring(1, params.length() - 1);
            for (String param : params.split(",")) {
                Matcher matcher = paramPattern.matcher(param);
                if (matcher.matches()) {
                    staticArgs.put(matcher.group(1), matcher.group(2));
                }
            }
        }

        public Map<String, String> matches(String method, String path) {
            if (method == null || this.method.equals("*") || method.equalsIgnoreCase(this.method)) {
                Matcher matcher = pattern.matcher(path);
                if (matcher.matches()) {
                	// Static dir
                	if(staticDir != null) {
                		throw new RenderStatic(staticDir + "/" + matcher.group("resource"));
                	} else {
	                    Map<String, String> localArgs = new HashMap<String, String>();
	                    for (Arg arg : args) {
	                        localArgs.put(arg.name, matcher.group(arg.name));
	                    }
	                    localArgs.putAll(staticArgs);
	                    return localArgs;
                	}
                }
            }
            return null;
        }

        static class Arg {

            String name;
            Pattern constraint;
            String defaultValue;
            Boolean optional = false;
        }

        @Override
        public String toString() {
            return method + " " + path + " -> " + action;
        }
    }
}
