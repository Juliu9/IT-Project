package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.inputparser.InputParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bounds raw request allocation even when the sender does not provide Content-Length. */
@ControllerAdvice(assignableTypes = {RequestGateway.class, DemoWebsite.class})
public class RequestBodyLimit extends RequestBodyAdviceAdapter {
  private static final int MAX_BYTES = InputParser.MAX_REQUEST_CHARACTERS * 4;

  @Override
  public boolean supports(
      MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return type == String.class;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    if (input.getHeaders().getContentLength() > MAX_BYTES) {
      throw tooLarge();
    }
    byte[] body = input.getBody().readNBytes(MAX_BYTES + 1);
    if (body.length > MAX_BYTES) {
      throw tooLarge();
    }
    return new HttpInputMessage() {
      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }

      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }
    };
  }

  private ResponseStatusException tooLarge() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large");
  }
}
