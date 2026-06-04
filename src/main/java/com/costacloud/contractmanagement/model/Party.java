package com.costacloud.contractmanagement.model;

import lombok.Data;

@Data
public class Party {
    private String id;
    private String label;
    private String color;
    private int order;
}
