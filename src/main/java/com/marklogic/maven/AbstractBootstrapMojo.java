package com.marklogic.maven;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.io.UnsupportedEncodingException;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.HttpEntity;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.entity.StringEntity;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.IOUtil;
import org.jfrog.maven.annomojo.annotations.MojoParameter;
import org.json.JSONException;
import org.json.JSONML;
import org.json.JSONObject;
import org.json.XML;
// 14-11-2014 Chris Hudson-Silver - Updated to work with MarkLogic 7 
public abstract class AbstractBootstrapMojo extends AbstractMarkLogicMojo {

    protected static final String XQUERY_PROLOG = "xquery version '1.0-ml';\n";

    protected static final String ML_ADMIN_MODULE_IMPORT = "import module namespace admin = 'http://marklogic.com/xdmp/admin' at '/MarkLogic/admin.xqy';\n";

    /**
     * The MarkLogic version.
     */
    @MojoParameter(defaultValue = "5", expression = "${marklogic.version}")
    protected int marklogicVersion;
    
    /**
     * The port used to bootstrap MarkLogic Server.
     */
    @MojoParameter(defaultValue = "8000", expression = "${marklogic.bootstrap.port}")
    protected int bootstrapPort;
    
    /**
     * The config manager port used to query MarkLogic Server.
     */
    @MojoParameter(defaultValue = "8002", expression = "${marklogic.config.port}")
    protected int configPort;
    
    /**
     * The server used to gain an sid for bootstrap creation.
     */
    @MojoParameter(defaultValue = "Admin", expression = "${marklogic.config.server}")
    protected String configServer;

    /**
     * The MarkLogic Installer XDBC server name.
     */
    @MojoParameter(defaultValue = "MarkLogic-Installer-XDBC", expression = "${marklogic.xdbc.name}")
    protected String xdbcName;
    
    /**
     * The MarkLogic group name.
     */
    @MojoParameter(defaultValue = "Default", expression = "${marklogic.group}")
    protected String group;

    /**
     * The MarkLogic Installer XDBC module root setting.
     */
    @MojoParameter(defaultValue = "/", expression = "${marklogic.xdbc.module-root}")
    protected String xdbcModuleRoot = "/";

    protected abstract String getBootstrapExecuteQuery() throws MojoExecutionException;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Install Bootstrap = " + installBootstrap);
        if (installBootstrap) {
        	String bootstrapQuery = getBootstrapExecuteQuery();
        	executeBootstrapQuery(bootstrapQuery);
        }
    }
    
	protected HttpResponse executeBootstrapQuery(String query)
			throws MojoExecutionException {
		HttpResponse response;
		if (marklogicVersion==7){
			response = executeML7BootstrapQuery(query);
		}
		else if (isMarkLogic5()) {
			getLog().info("Bootstrapping MarkLogic 5");
			response = executeML5BootstrapQuery(query);
		} else {
			getLog().info("Bootstrapping MarkLogic 4");
			response =  executeML4BootstrapQuery(query);
		}
		
		if (response.getEntity() != null) {
			try {
				InputStream is = response.getEntity().getContent();
				getLog().info(IOUtil.toString(is));
			} catch (IOException e) {
				throw new MojoExecutionException("IO Error reading response", e);
			}
		}
		
		return response;
	}
    
    protected HttpResponse executeML4BootstrapQuery(String query) throws MojoExecutionException {
        HttpClient httpClient = this.getHttpClient();
        List<NameValuePair> qparams = new ArrayList<NameValuePair>();        
        qparams.add(new BasicNameValuePair("queryInput", query));
        
        URI uri;
        try {
            uri = URIUtils.createURI("http", this.host, bootstrapPort, "/use-cases/eval2.xqy",
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for bootstrap query", e1);
        }

        HttpPost httpPost = new HttpPost(uri);
        
        HttpResponse response;
        
        try {
            response = httpClient.execute(httpPost);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing POST to create bootstrap server", e);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MojoExecutionException("POST response failed: " + response.getStatusLine());
        }

        return response;
    }
    
    protected HttpResponse executeML5BootstrapQuery(String query) throws MojoExecutionException {
        HttpClient httpClient = this.getHttpClient();
        List<NameValuePair> qparams = new ArrayList<NameValuePair>();
        qparams.add(new BasicNameValuePair("group-id", group));
        qparams.add(new BasicNameValuePair("format", "json"));

        URI uri;
        try {
            uri = URIUtils.createURI("http", this.host, configPort, "/manage/v1/servers/" + configServer,
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for querying " + configServer + " for it's id", e1);
        }

        HttpGet httpGet = new HttpGet(uri);

        HttpResponse response;
        String sid = null;
       

        try {
            response = httpClient.execute(httpGet);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing GET " + uri, e);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MojoExecutionException("GET response failed: " + response.getStatusLine());
        }
        
		try {
			if (response.getEntity() != null) {
				InputStream is = response.getEntity().getContent();
				JSONObject json = new JSONObject(IOUtil.toString(is));
				sid = json.getJSONObject("server-default").getString("id");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Error parsing json response to get " + configServer + " server id", e);
		}
        
        
        if (sid == null) {
			throw new MojoExecutionException("Server id for " + configServer + " is null, aborting");
        }
        
        qparams.clear();
        qparams.add(new BasicNameValuePair("q", query));
        qparams.add(new BasicNameValuePair("resulttype", "xml"));
        qparams.add(new BasicNameValuePair("sid", sid));

        try {
            uri = URIUtils.createURI("http", this.host, bootstrapPort, "/qconsole/endpoints/eval.xqy",
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for bootstrap query", e1);
        }

        HttpPost httpPost = new HttpPost(uri);

        try {
            response = httpClient.execute(httpPost);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing POST to create bootstrap server", e);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MojoExecutionException("POST response failed: " + response.getStatusLine());
        }

		Header[] headers = response.getHeaders("qconsole");
		if (headers != null && headers.length > 0) {
			try {
				JSONObject json = new JSONObject(headers[0].getValue());
				if (json.getString("type").equals("error")) {
					StringBuilder b = new StringBuilder(
							"Failed to execute query ...\n");
					if (response.getEntity() != null) {
						InputStream is = response.getEntity().getContent();
						JSONObject jsonError = new JSONObject(
								IOUtil.toString(is));
						b.append(XML.toString(jsonError));
					}
					throw new MojoExecutionException(b.toString());
				}
			} catch (JSONException e) {
				throw new MojoExecutionException(
						"Unable to parse json error header", e);
			} catch (IOException ioe) {
				throw new MojoExecutionException(
						"IOException parsing json error", ioe);
			}
		}

        return response;
    }
	
	protected HttpResponse executeML7BootstrapQuery(String query) throws MojoExecutionException {
        HttpClient httpClient = this.getHttpClient();
        List<NameValuePair> qparams = new ArrayList<NameValuePair>();
        qparams.add(new BasicNameValuePair("group-id", group));
        qparams.add(new BasicNameValuePair("format", "json"));

        URI uri;
        try {
            uri = URIUtils.createURI("http", this.host, configPort, "/manage/v2/servers/" + configServer,
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for querying " + configServer + " for it's id", e1);
        }

        HttpGet httpGet = new HttpGet(uri);

        HttpResponse response;
        String sid = null;
       

        try {
            response = httpClient.execute(httpGet);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing GET " + uri, e);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MojoExecutionException("GET response failed: " + response.getStatusLine());
        }
        
		try {
			if (response.getEntity() != null) {
				InputStream is = response.getEntity().getContent();
				JSONObject json = new JSONObject(IOUtil.toString(is));
				sid = json.getJSONObject("server-default").getString("id");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Error parsing json response to get " + configServer + " server id", e);
		}
        
        
        if (sid == null) {
			throw new MojoExecutionException("Server id for " + configServer + " is null, aborting");
        }
		
		
        try {
            uri = URIUtils.createURI("http", this.host, configPort, "/manage/v2/databases/Modules",
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for querying " + configServer + " for it's id", e1);
        }

        httpGet = new HttpGet(uri);

        
        
        qparams.clear();
        qparams.add(new BasicNameValuePair("sid", sid));
		qparams.add(new BasicNameValuePair("action", "eval"));
		qparams.add(new BasicNameValuePair("querytype", "xquery"));
		
		
		
        try {
            uri = URIUtils.createURI("http", this.host, bootstrapPort, "/qconsole/endpoints/evaler.xqy",
                    URLEncodedUtils.format(qparams, "UTF-8"), null);
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Invalid uri for bootstrap query", e1);
        }

        HttpPost httpPost = new HttpPost(uri);
		try{
			HttpEntity entity = new StringEntity(query);
			httpPost.setEntity(entity);
        } catch ( UnsupportedEncodingException uee){
			throw new MojoExecutionException("unable to set query"+ uee);
		}
		try {
            response = httpClient.execute(httpPost);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing POST to create bootstrap server", e);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MojoExecutionException("POST response failed: " + response.getStatusLine());
        }

		Header[] headers = response.getHeaders("qconsole");
		if (headers != null && headers.length > 0) {
			try {
				JSONObject json = new JSONObject(headers[0].getValue());
				if (json.getString("type").equals("error")) {
					StringBuilder b = new StringBuilder(
							"Failed to execute query ...\n");
					if (response.getEntity() != null) {
						InputStream is = response.getEntity().getContent();
						JSONObject jsonError = new JSONObject(
								IOUtil.toString(is));
						b.append(XML.toString(jsonError));
					}
					throw new MojoExecutionException(b.toString());
				}
			} catch (JSONException e) {
				throw new MojoExecutionException(
						"Unable to parse json error header", e);
			} catch (IOException ioe) {
				throw new MojoExecutionException(
						"IOException parsing json error", ioe);
			}
		}

        return response;
    }

	protected boolean isMarkLogic5() throws MojoExecutionException {
	    HttpClient httpClient = this.getHttpClient();
	    List<NameValuePair> qparams = new ArrayList<NameValuePair>();
	    URI uri;
	
		try {
	        uri = URIUtils.createURI("http", this.host, bootstrapPort, "/qconsole",
	                URLEncodedUtils.format(qparams, "UTF-8"), null);
	    } catch (URISyntaxException e1) {
	        throw new MojoExecutionException("Invalid uri for qconsole probe", e1);
	    }
	
	    HttpGet httpGet = new HttpGet(uri);
	
	    HttpResponse response;
		try {
	        response = httpClient.execute(httpGet);
	        if (response.getEntity() != null) {
	        	response.getEntity().getContent();
	        }
	    } catch (Exception e) {
	        throw new MojoExecutionException("Error executing GET to proble qconsole", e);
	    }
	    
	    getLog().debug("Probe got " + response.getStatusLine());
	    
	    return (response.getStatusLine().getStatusCode() == 200);
	}

}
