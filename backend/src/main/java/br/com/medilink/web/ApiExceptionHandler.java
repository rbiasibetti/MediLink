package br.com.medilink.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Erros em ProblemDetail (RFC 9457). O tratamento padrão de validação descreve só os campos, sem
 * devolver o valor rejeitado, então o CPF digitado não vaza na resposta.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
}
