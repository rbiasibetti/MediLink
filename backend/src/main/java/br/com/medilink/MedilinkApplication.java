package br.com.medilink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MedilinkApplication {

	public static void main(String[] args) {
		SpringApplication.run(MedilinkApplication.class, args);
	}

}
