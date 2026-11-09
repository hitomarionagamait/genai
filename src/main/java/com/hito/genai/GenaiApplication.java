package com.hito.genai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.hito.genai")
public class GenaiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GenaiApplication.class, args);
	}

}
