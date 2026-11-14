# Delivery Receipts API Contract

The Receipts API is provided by `parcel-pricing-service` (S2) to allow customers, merchants, and operations staff to retrieve official billing receipts and printable PDF documents for delivery bookings.

---

## 1. Endpoints

### 1.1 Get Receipt Details
`GET /api/v1/parcels/{id}/receipt`

#### Request
- **Headers:**
  - `Authorization: Bearer <jwt-token>`
- **Path Parameters:**
  - `id`: UUID of the parcel booking or merchant order

#### Access Control
- Allowed Roles: `CUSTOMER`, `MERCHANT`, `DISPATCHER`, `SERVICE`
- `CUSTOMER`: Can only access bookings where `customer_subject == jwt.subject`.
- `MERCHANT`: Can only access orders where `merchant_id == jwt.subject`.
- `DISPATCHER` / `SERVICE`: Unrestricted access across all bookings.
- Unauthorized or cross-owner access returns `404 Not Found` to prevent resource enumeration.

#### Response: `200 OK` (`application/json`)
```json
{
  "id": "c1f7a0b3-4f91-4d92-8e12-3a5e8f9b0c1d",
  "receiptNumber": "REC-7K3QX9",
  "parcelId": "7a3e81b2-2b4c-4e5a-8b3d-1a9f0e2d3c4b",
  "trackingRef": "ZT-7K3QX9M2PA",
  "amount": 1250.00,
  "formattedAmount": "LKR 1,250.00",
  "currency": "LKR",
  "paymentStatus": "Paid",
  "paymentMethod": "Credit Card (•••• 4242)",
  "downloadUrl": "/api/v1/parcels/7a3e81b2-2b4c-4e5a-8b3d-1a9f0e2d3c4b/receipt/pdf",
  "date": "2026-11-10T10:00:00Z",
  "formattedDate": "10 Nov 2026",
  "senderName": "John Sender",
  "recipientName": "Jane Recipient"
}
```

---

### 1.2 Download Printable Receipt PDF
`GET /api/v1/parcels/{id}/receipt/pdf`

#### Request
- **Headers:**
  - `Authorization: Bearer <jwt-token>`
- **Path Parameters:**
  - `id`: UUID of the parcel booking or merchant order

#### Access Control
- Same scoping rules as `GET /api/v1/parcels/{id}/receipt`.

#### Response: `200 OK` (`application/pdf`)
- **Headers:**
  - `Content-Type: application/pdf`
  - `Content-Disposition: inline; filename="receipt-ZT-7K3QX9M2PA.pdf"`
- **Body:** Binary PDF stream containing printable receipt document.

---

## 2. Integrity and Non-Repricing Invariant
All receipt data is generated directly from the immutable persisted booking record stored in `parcel.parcel`. Under no circumstances is the pricing engine (`PricingService`) re-invoked during receipt generation, ensuring exact price consistency, currency preservation, and prevention of re-calculation drift.
