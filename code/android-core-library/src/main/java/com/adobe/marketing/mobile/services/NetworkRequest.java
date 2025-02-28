package com.adobe.marketing.mobile.services;

import java.util.Map;

// NetworkRequest class contains the data needed to initiate a network request
public class NetworkRequest {
	private final String url;
	private final HttpMethod method;
	private final byte[] body;
	private final Map<String, String> headers;
	private final int connectTimeout;
	private final int readTimeout;

	/**
	 * Constructor for NetworkRequest
	 * @param url {@link String} containing the full url for connection
	 * @param method {@link com.adobe.marketing.mobile.services.HttpMethod}, for example "POST", "GET" etc.
	 * @param body {@code byte[]} array specifying payload to send to the server
	 * @param headers {@code Map<String, String>} containing any additional key value pairs to be used while requesting a
	 *                        connection to the url depending on the {@code method} used
	 * @param connectTimeout {@code int} indicating connect timeout value in seconds
	 * @param readTimeout {@code int} indicating the timeout, in seconds, that will be used to wait for a read to finish after a successful connect
	 */
	public NetworkRequest(final String url,
						  final HttpMethod method,
						  final byte[] body,
						  final Map<String, String> headers,
						  final int connectTimeout,
						  final int readTimeout) {
		this.method = method;
		this.body = body;
		this.url = url;
		this.headers = headers;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
	}

	public String getUrl() {
		return url;
	}

	public HttpMethod getMethod() {
		return method;
	}

	public byte[] getBody() {
		return body;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public int getConnectTimeout() {
		return connectTimeout;
	}

	public int getReadTimeout() {
		return readTimeout;
	}
}
