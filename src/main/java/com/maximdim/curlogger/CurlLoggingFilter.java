package com.maximdim.curlogger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java Servlet filter to log HTTP requests in curl format so they can be re-run from command line
 * 
 * Should be added to web.xml, for example:
 * 
 * <filter>
 * 	<filter-name>requestLoggingFilter</filter-name>
 * 	<filter-class>com.maximdim.curlogger.CurlLoggingFilter</filter-class>
 * </filter>
 * <filter-mapping>
 * 	<filter-name>requestLoggingFilter</filter-name>
 * 	<url-pattern>/*</url-pattern>
 * </filter-mapping>
 * 
 * @author Dmitri Maximovich <maxim@maximdim.com>
 */
public class CurlLoggingFilter implements Filter {
	private static final int BUFFER_SIZE = 4096;
	private static final Logger logger = LoggerFactory.getLogger(CurlLoggingFilter.class);

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// empty
	}

	@Override
	public void destroy() {
		// empty
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
		RequestWrapper reqestWrapper = new RequestWrapper((HttpServletRequest)req);

		logger.debug(getCurl(reqestWrapper));
		
		chain.doFilter(reqestWrapper, resp);
	}

    private String getCurl(RequestWrapper req) {
		Charset charset = getCharset(req);
		String body = req.hasBody() ? req.getBody(charset) : null;

    	StringBuilder sb = new StringBuilder();

        sb.append("curl")
	        .append(" -X ").append(req.getMethod())
	        .append(" \"").append(getFullRequest(req)).append("\"");

        // headers
        Enumeration<String> headerNames = req.getHeaderNames();
		if (headerNames != null) {
			while(headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				sb.append(" -H \"").append(headerName).append(": ").append(req.getHeader(headerName)).append("\"");
			}
		}
		
		// body
		if (body != null) {
			// escape quotes
			body = body.replaceAll("\"", "\\\\\"");
			sb.append(" -d \"").append(body).append("\"");
		}
        return sb.toString();
    }
	
    private String getFullRequest(HttpServletRequest req) {
    	StringBuilder sb = new StringBuilder();
    	sb.append(req.getRequestURL().toString());

    	String sep = "?";
    	for(Map.Entry<String, String[]> me: req.getParameterMap().entrySet()) {
    		for(String value: me.getValue()) {
    			sb.append(sep).append(me.getKey()).append("=").append(value);
        		sep = "&";
    		}
        }
    	return sb.toString();
    }
    
	private static final class RequestWrapper extends HttpServletRequestWrapper {
		private byte[] requestInputStream;

		public RequestWrapper(HttpServletRequest req) throws IOException {
			super(req);

			try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
				copy(req.getInputStream(), os);
				// do we need to close inputStream?
				requestInputStream = os.toByteArray();
			}
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			return new BufferedServletInputStream(this.requestInputStream);
		}

		boolean hasBody() {
			return this.requestInputStream != null && requestInputStream.length != 0;
		}

		String getBody(Charset charset) {
			return hasBody() ? arrayToString(this.requestInputStream, charset) : null;
		}
	} 

	private static final class BufferedServletInputStream extends ServletInputStream {
		private final ByteArrayInputStream is;

		public BufferedServletInputStream(byte[] buffer) {
			this.is = new ByteArrayInputStream(buffer);
		}

		@Override
		public int available() {
			return this.is.available();
		}

		@Override
		public int read() {
			return this.is.read();
		}

		@Override
		public int read(byte[] buf, int off, int len) {
			return this.is.read(buf, off, len);
		}

		@Override
		public boolean isFinished() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isReady() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * @return true if byte array is gzipped by looking at gzip magic
	 */
	static boolean isGzip(byte[] bytes) {
		if (bytes.length < 2) {
			return false;
		}
		int head = (bytes[0] & 0xff) | ((bytes[1] << 8) & 0xff00);
		return GZIPInputStream.GZIP_MAGIC == head;
	}	
	
	static String arrayToString(byte[] arr, Charset charset) {
		// content could be compressed
		if (isGzip(arr)) {
			return fromGzipped(arr, charset);
		}
		return new String(arr, charset);
	}

	private static String fromGzipped(byte[] arr, Charset charset) {
		try(GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(arr))) {
			return copyToString(gis, charset);
		} 
		catch (IOException e) {
			logger.warn("Error converting to String: "+e.getMessage());
			return "";
		}
	}
	
	private static Charset getCharset(HttpServletRequest req) {
		String encoding = req.getCharacterEncoding();
		if (encoding != null && !encoding.isEmpty()) {
			try {
				return Charset.forName(encoding);
			}
			catch (UnsupportedCharsetException e) {
				logger.warn("Unsupported charset [{}]: {}", encoding, e.getMessage());
			}
		}
		return Charset.defaultCharset();
	}

	static int copy(InputStream in, OutputStream out) throws IOException {
		int byteCount = 0;
		byte[] buffer = new byte[BUFFER_SIZE];
		int bytesRead = -1;
		while ((bytesRead = in.read(buffer)) != -1) {
			out.write(buffer, 0, bytesRead);
			byteCount += bytesRead;
		}
		out.flush();
		return byteCount;
	}
	
	static String copyToString(InputStream in, Charset charset) throws IOException {
		StringBuilder out = new StringBuilder();
		InputStreamReader reader = new InputStreamReader(in, charset);
		char[] buffer = new char[BUFFER_SIZE];
		int bytesRead = -1;
		while ((bytesRead = reader.read(buffer)) != -1) {
			out.append(buffer, 0, bytesRead);
		}
		return out.toString();
	}
	
}
