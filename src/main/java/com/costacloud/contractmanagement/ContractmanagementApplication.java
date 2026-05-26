package com.costacloud.contractmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ContractmanagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(ContractmanagementApplication.class, args);
	}

}
