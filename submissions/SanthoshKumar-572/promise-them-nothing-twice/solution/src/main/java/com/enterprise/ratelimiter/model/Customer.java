package com.enterprise.ratelimiter.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private String customerId;
    private String plan;
    private int normalLimit;
    private Integer specialLimit;
    private String specialWindow; // format "HH:mm-HH:mm" (e.g. "02:00-04:00")

    public Customer() {
    }

    public Customer(String customerId, String plan, int normalLimit, Integer specialLimit, String specialWindow) {
        this.customerId = customerId;
        this.plan = plan;
        this.normalLimit = normalLimit;
        this.specialLimit = specialLimit;
        this.specialWindow = specialWindow;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public int getNormalLimit() {
        return normalLimit;
    }

    public void setNormalLimit(int normalLimit) {
        this.normalLimit = normalLimit;
    }

    public Integer getSpecialLimit() {
        return specialLimit;
    }

    public void setSpecialLimit(Integer specialLimit) {
        this.specialLimit = specialLimit;
    }

    public String getSpecialWindow() {
        return specialWindow;
    }

    public void setSpecialWindow(String specialWindow) {
        this.specialWindow = specialWindow;
    }
}
