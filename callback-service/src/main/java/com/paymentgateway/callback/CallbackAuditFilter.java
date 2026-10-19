package com.paymentgateway.callback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.paymentgateway.audit.service.ApiCallAuditService;
import com.paymentgateway.audit.util.AuditJson;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filters requests for callback audit processing and security enforcement.
 */
@Component
public class CallbackAuditFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger(CallbackAuditFilter.class);
	private final ApiCallAuditService audit;

	/**
	 * Creates a new {@code CallbackAuditFilter} instance.
	 *
	 * @param audit the audit value
	 */
	public CallbackAuditFilter(ApiCallAuditService audit) {
		this.audit = audit;
	}

	/**
	 * Executes the should not filter operation.
	 *
	 * @param request the current request or request payload
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/callbacks/");
	}

	/**
	 * Executes the do filter internal operation.
	 *
	 * @param request  the current request or request payload
	 * @param response the current response or response payload
	 * @param chain    the chain value
	 * @throws ServletException if servlet processing fails
	 * @throws IOException      if an I/O operation fails
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var req = new ContentCachingRequestWrapper(request, 1024 * 1024);
		var res = new ContentCachingResponseWrapper(response);
		String correlationId = request.getHeader("X-Correlation-Id");
		String orderId = req.getParameter("orderId");
		if (orderId == null)
			orderId = req.getParameter("invoiceNo");
		String uri = request.getRequestURI();
		String provider = uri.contains("/mpu/") ? "MPU" : (uri.contains("/ayapay") ? "AYAPAY" : "ABANK_APLUS");
		String operation = provider + "_CALLBACK_"
				+ request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1).toUpperCase();
		UUID callId = audit.start("callback-service", "INBOUND", provider, "PAYMENT_GATEWAY", operation,
				request.getMethod(), request.getRequestURI(), request.getQueryString(),
				AuditJson.write(redactedHeaders(request)), null, correlationId, null, null, orderId);
		Throwable failure = null;
		try {
			chain.doFilter(req, res);
		} catch (Throwable t) {
			failure = t;
			if (t instanceof ServletException se)
				throw se;
			if (t instanceof IOException ioe)
				throw ioe;
			if (t instanceof RuntimeException re)
				throw re;
			throw new ServletException(t);
		} finally {
			String requestBody = text(req.getContentAsByteArray());
			String responseBody = text(res.getContentAsByteArray());
			try {
				if (failure == null)
					audit.complete(callId, requestBody, res.getStatus(), AuditJson.write(responseHeaders(res)),
							responseBody);
				else
					audit.fail(callId, requestBody, res.getStatus(), responseBody, failure);
			} catch (RuntimeException auditError) {
				log.error("Failed to finalize callback audit record {}", callId, auditError);
			}
			res.copyBodyToResponse();
		}
	}

	/**
	 * Executes the redacted headers operation.
	 *
	 * @param r the refund or provider result
	 * @return the resulting key-value data
	 */
	private static Map<String, String> redactedHeaders(HttpServletRequest r) {
		Map<String, String> m = new LinkedHashMap<>();
		var names = r.getHeaderNames();
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			String lower = name.toLowerCase(Locale.ROOT);
			String value = lower.equals("authorization") || lower.equals("cookie") || lower.contains("secret")
					|| lower.contains("token") ? "***REDACTED***" : r.getHeader(name);
			m.put(name, value);
		}
		return m;
	}

	/**
	 * Executes the response headers operation.
	 *
	 * @param r the refund or provider result
	 * @return the resulting key-value data
	 */
	private static Map<String, String> responseHeaders(HttpServletResponse r) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String name : r.getHeaderNames()) {
			String lower = name.toLowerCase(Locale.ROOT);
			String value = lower.equals("set-cookie") || lower.equals("authorization") || lower.contains("token")
					? "***REDACTED***"
					: String.join(",", r.getHeaders(name));
			m.put(name, value);
		}
		return m;
	}

	/**
	 * Executes the text operation.
	 *
	 * @param bytes the bytes value
	 * @return the resulting text text
	 */
	private static String text(byte[] bytes) {
		return bytes == null || bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
	}
}
