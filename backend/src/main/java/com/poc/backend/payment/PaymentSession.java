package com.poc.backend.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One attempt to pay a case's state fee, created by {@code POST /api/public/payments/{token}
 * /checkout} and settled only by the payment provider's signed callback (docs/security.md rule 4).
 *
 * <p>The amount is computed on the server when the session is created; the callback must report
 * exactly this amount and currency or it is refused. {@code @Version} makes two concurrent
 * callbacks for the same session collide instead of both correlating the payment.
 */
@Entity
@Table(
    name = "payment_sessions",
    indexes = {@Index(name = "idx_payment_sessions_pi", columnList = "processInstanceId")})
public class PaymentSession {

  /** Lifecycle of a session; only the provider callback moves it out of {@link #PENDING}. */
  public enum Status {
    PENDING,
    PAID,
    CANCELLED
  }

  @Id private String id;

  @Column(nullable = false)
  private String processInstanceId;

  @Column(nullable = false)
  private String processDefinitionKey;

  @Column(nullable = false)
  private String serviceName;

  @Column(nullable = false)
  private String recipient;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false)
  private String reference;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant paidAt;

  @Version private Long version;

  protected PaymentSession() {
    // JPA
  }

  public PaymentSession(
      String id,
      String processInstanceId,
      String processDefinitionKey,
      String serviceName,
      String recipient,
      BigDecimal amount,
      String currency,
      String reference,
      Instant createdAt) {
    this.id = id;
    this.processInstanceId = processInstanceId;
    this.processDefinitionKey = processDefinitionKey;
    this.serviceName = serviceName;
    this.recipient = recipient;
    this.amount = amount;
    this.currency = currency;
    this.reference = reference;
    this.status = Status.PENDING.name();
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public String getProcessDefinitionKey() {
    return processDefinitionKey;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getRecipient() {
    return recipient;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getReference() {
    return reference;
  }

  public Status getStatus() {
    return Status.valueOf(status);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public void markPaid(Instant at) {
    this.status = Status.PAID.name();
    this.paidAt = at;
  }

  public void markCancelled() {
    this.status = Status.CANCELLED.name();
  }
}
