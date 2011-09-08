package com.grails.cxf.client

import com.grails.cxf.client.exception.CxfClientException
import com.grails.cxf.client.exception.UpdateServiceEndpointException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.security.auth.callback.Callback
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.UnsupportedCallbackException
import org.apache.commons.logging.LogFactory
import org.apache.cxf.BusFactory
import org.apache.cxf.endpoint.Client
import org.apache.cxf.frontend.ClientProxy
import org.apache.cxf.interceptor.LoggingInInterceptor
import org.apache.cxf.interceptor.LoggingOutInterceptor
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean
import org.apache.cxf.transport.Conduit
import org.apache.cxf.transport.http.HTTPConduit
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor
import org.apache.ws.security.WSPasswordCallback
import org.apache.ws.security.handler.WSHandlerConstants
import groovy.transform.Synchronized

class WebServiceClientFactoryImpl implements WebServiceClientFactory {

    private static final log = LogFactory.getLog(this)
    /**
     * These fields are used only on secure connections.<br>
     * eg: we have a servicename key defined as ServiceNameWS
     * The username and password are expected to be ServiceNameWSUsername and ServiceNameWSPassword<br>
     * The Username suffix and password suffix are used to build the key used to retrieve the username
     * and password respectively.
     */
    private Map<String, Class<?>> interfaceMap = [:]
    private Map<Class<?>, WSClientInvocationHandler> handlerMap = [:]
    private Map<String, Map<String, String>> securityMap = [:]

    WebServiceClientFactoryImpl() {
    }
    
    @Synchronized Object getWebServiceClient(Class<?> clientInterface, String serviceName, String serviceEndpointAddress, boolean secured, String username, String password) {
        WSClientInvocationHandler handler = new WSClientInvocationHandler(clientInterface)
        Object clientProxy = Proxy.newProxyInstance(clientInterface.classLoader, [clientInterface] as Class[], handler)
        // is used only in secure mode to extract the username/password
        if(serviceEndpointAddress) {
            try {
                if(log.isDebugEnabled()) log.debug("Creating endpoint for service $serviceName using endpoint address $serviceEndpointAddress is secured $secured")
                createCxfProxy(clientInterface, serviceEndpointAddress, secured, serviceName, username, password,  handler)
            } catch (Exception exception) {
                CxfClientException cxfClientException = new CxfClientException("Could not create web service client for interface $clientInterface with Service Endpoint Address at $serviceEndpointAddress.  Make sure Endpoint URL exists and is accessible.", exception)
                if(log.isErrorEnabled()) log.error(cxfClientException.message, cxfClientException)
                throw cxfClientException
            }

        } else {
            CxfClientException cxfClientException = new CxfClientException("Web service client failed to initialize with url: $serviceEndpointAddress using secured: $secured")
            if(log.isErrorEnabled()) log.error(cxfClientException.message, cxfClientException)
            throw cxfClientException
        }

        if(log.isDebugEnabled()) log.debug("Created service $serviceName, caching reference to allow changing url later.")
        interfaceMap.put(serviceName, clientInterface)
        handlerMap.put(clientInterface, handler)
        securityMap.put(serviceName, [username: username, password: password])

        return clientProxy
    }

    /**
     * Method to allow updating endpoint and refreshing proxy reference
     * @param serviceName The name of the service to update
     * @param serviceEndpointAddress The new address to use
     * @param secured Whether the service is secured or not
     * @throws UpdateServiceEndpointException If endpoint can not be updated
     */
    @Synchronized void updateServiceEndpointAddress(String serviceName, String serviceEndpointAddress, boolean secured) throws UpdateServiceEndpointException {
        if(log.isDebugEnabled()) log.debug("Changing the service $serviceName endpoint address to $serviceEndpointAddress")

        if(!serviceName || !interfaceMap.containsKey(serviceName)) {
            throw new UpdateServiceEndpointException("Can not update address for service.  Must provide a service name.")
        }

        Class<?> clientInterface = interfaceMap.get(serviceName)
        Map<String, String> security = securityMap.get(serviceName)
        if(clientInterface) {
            WSClientInvocationHandler handler = handlerMap.get(clientInterface)
            // is used only in secure mode to extract the username/password
            try {
                createCxfProxy(clientInterface, serviceEndpointAddress, secured, serviceName, security.username, security.password, handler)
                if(log.isDebugEnabled()) log.debug("Successfully changed the service $serviceName endpoint address to $serviceEndpointAddress")
            } catch (Exception exception) {
                handler.cxfProxy = null
                throw new UpdateServiceEndpointException("Could not create web service client for Service Endpoint Address at $serviceEndpointAddress.  Make sure Endpoint URL exists and is accessible.", exception)
            }
        } else {
            if(log.isDebugEnabled()) log.debug("Unable to find existing client proxy matching name ${serviceName}")
        }
    }

    private void createCxfProxy(Class<?> serviceInterface, String serviceEndpointAddress, boolean secured, String serviceName, String username, String password, WSClientInvocationHandler handler) {
        JaxWsProxyFactoryBean clientProxyFactory = new JaxWsProxyFactoryBean(serviceClass: serviceInterface,
                                                                             address: serviceEndpointAddress,
                                                                             bus: BusFactory.getDefaultBus())
        Object cxfProxy = clientProxyFactory.create()
        if(secured) {
            secureClient(cxfProxy, username, password)
        }
        addInterceptors(cxfProxy)
        handler.cxfProxy = cxfProxy
    }

    private void addInterceptors(Object cxfProxy) {
        Client client = ClientProxy.getClient(cxfProxy)
        client.getOutFaultInterceptors().add(new CxfClientFaultConverter())
        client.inInterceptors.add(new LoggingInInterceptor())
        client.outInterceptors.add(new LoggingOutInterceptor())
    }

    private void secureClient(Object cxfProxy, String username, String password) {
        /* String wsClientAlias = WebServiceClientConstants.WS_CLIENT_ALIAS
        if (wsClientAlias == null) {
            throw new RuntimeException("Both System properties " + WebServiceClientConstants.WS_CLIENT_ALIAS_PROP_NAME + " and " + WebServiceClientConstants.WS_CLIENT_PWD_PROP_NAME + " must be defined")
        }
        */
        if(username?.trim()?.length() < 1 || password?.length() < 1) {
            throw new RuntimeException("Username and password are not configured for calling secure web services")
        }

        Client client = ClientProxy.getClient(cxfProxy)
        // applies the policy to the request
        configurePolicy(client)

        Map<String, Object> outProps = [:]
        outProps.put(WSHandlerConstants.ACTION, org.apache.ws.security.handler.WSHandlerConstants.USERNAME_TOKEN)
        // User in keystore
        outProps.put(WSHandlerConstants.USER, username)
        outProps.put(WSHandlerConstants.PASSWORD_TYPE, org.apache.ws.security.WSConstants.PW_TEXT)
        outProps.put(WSHandlerConstants.PW_CALLBACK_REF, new CallbackHandler() {

            void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                WSPasswordCallback pc = (WSPasswordCallback) callbacks[0]
                pc.password = password
            }
        })
        // This callback is used to specify passwomrd for given user for keystore
        //outProps.put(WSHandlerConstants.PW_CALLBACK_CLASS, PasswordHandler.class.getName())
        // Configuration for accessing private key in keystore
        //outProps.put(WSHandlerConstants.SIG_PROP_FILE, "resources/SecurityOut.properties")
        //outProps.put(WSHandlerConstants.SIG_KEY_ID, "DirectReference")

        //        client.getOutInterceptors().add(new DOMOutputHandler())
        client.outInterceptors.add(new WSS4JOutInterceptor(outProps))
    }

    /**
     * Applies the Client policy on the Http Conduit.
     * Notes: <br>
     * Conduit handles both the Http and Https protocols. <br>
     * Policies apply to the Conduit. Chunking is applied on the policy to control the mode of transmission.
     * In non-chunking mode, the message is expected to be sent as a single block. In chunking mode, the message
     * can be sent to the server while its being constructed. The server and client are expected to work in parallel during
     * message transmission. <br>CXF by default runs in chunking mode. In addition, chunking mode is not supported by some web services.
     * Thus, Chunking was disabled on the Client policy.
     *
     * @param client
     */
    private void configurePolicy(Client client) {
        Conduit c = client.conduit
        if(c instanceof HTTPConduit) {
            HTTPConduit conduit = (HTTPConduit) c
            conduit.client = new HTTPClientPolicy(connectionTimeout: 0, allowChunking: false)
        }
    }

    /**
     * Internal class to invoke the proxy
     */
    private class WSClientInvocationHandler implements InvocationHandler {

        Object cxfProxy
        String clientName

        WSClientInvocationHandler(Class<?> clientInterface) {
            this.clientName = clientInterface.name
        }

        Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if(!cxfProxy) {
                    throw new RuntimeException("Error invoking method ${method.name} on interface $clientName. Proxy must have failed to initialize.")
                }
                method.invoke(cxfProxy, args)
            } catch (Exception e) {
                println e.message
                throw new CxfClientException("Error invoking method ${method.name} on interface $clientName. Make sure valid clientInterface and serviceEndpointAddress are set.", e)
            }
        }
    }
}
