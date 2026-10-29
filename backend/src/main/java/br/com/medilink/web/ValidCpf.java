package br.com.medilink.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import br.com.medilink.service.Cpf;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ValidCpf.Validator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface ValidCpf {

	String message() default "CPF inválido";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/** Null é válido: a obrigatoriedade fica com @NotBlank. */
	class Validator implements ConstraintValidator<ValidCpf, String> {

		@Override
		public boolean isValid(String valor, ConstraintValidatorContext ctx) {
			return valor == null || Cpf.valido(valor);
		}
	}
}
