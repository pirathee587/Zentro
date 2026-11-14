#!/usr/bin/env python3
"""Generate the checked-in OpenAPI 3.1 contracts from this declarative REST inventory."""
from __future__ import annotations

import json
import fnmatch
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "openapi"
CORRELATION_SCHEMA = {"type": "string", "minLength": 1, "maxLength": 128,
                      "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"}


def field(kind="string", **kw):
    return {"type": kind, **kw}


def obj(properties, required=(), **kw):
    return {"type": "object", "properties": properties, **({"required": list(required)} if required else {}), **kw}


def arr(schema):
    return {"type": "array", "items": schema}


UUID = field("string", format="uuid")
DATE = field("string", format="date-time")
DEC = field("number")
STR = field()
BOOL = field("boolean")
INT = field("integer")
NULLSTR = {"anyOf": [STR, {"type": "null"}]}

PROBLEM = obj({
    "type": field("string", format="uri-reference"), "title": STR, "status": INT,
    "detail": STR, "instance": field("string", format="uri-reference"), "code": STR,
    "fieldErrors": {"type": "object", "additionalProperties": STR},
    "invalidFields": {"type": "object", "additionalProperties": STR},
    "currentStatus": STR, "requestedStatus": STR, "allowedNext": arr(STR),
}, ("title", "status", "detail"))

CONTACT = obj({"name": field(maxLength=100), "phone": field(description="Remove ASCII whitespace and dashes, then require +94 or 0 followed by nine ASCII digits."),
               "address": field(minLength=8, maxLength=255, description="Unicode code points after existing trimming; control, format and unpaired surrogate characters are rejected."), "city": {"type": ["string", "null"], "maxLength": 100}}, ("name", "phone", "address"))
PARCEL = obj({"weightKg": field("number", minimum=0.1, maximum=20, multipleOf=0.0001), "category": field(minLength=1, maxLength=40),
              "size": {"type": ["string", "null"], "enum": ["SMALL", "MEDIUM", "LARGE", None]},
              "description": {"type": ["string", "null"], "maxLength": 255}}, ("weightKg", "category"))
BOOKING_REQUEST = obj({"sender": CONTACT, "recipient": CONTACT, "parcel": PARCEL,
                       "destinationType": {"type": ["string", "null"], "enum": ["SAME_CITY", "OTHER_DISTRICT", None]},
                       "isExpress": {"type": ["boolean", "null"]}, "notes": {"type": ["string", "null"], "maxLength": 500}},
                      ("sender", "recipient", "parcel"))
BOOKING_RESPONSE = obj({"id": UUID, "trackingRef": STR, "status": STR, "fee": DEC, "currency": STR,
                        "estimatedDays": INT, "createdAt": DATE, "sender": CONTACT, "recipient": CONTACT,
                        "parcel": PARCEL})
ERROR_RESP = {"description": "RFC 9457 problem response", "content": {"application/problem+json": {
    "schema": {"$ref": "#/components/schemas/Problem"},
    "examples": {
        "validation": {"summary": "Validation response with request field errors", "value": {
            "type": "about:blank", "title": "Bad Request", "status": 400,
            "detail": "Validation failed for request.", "instance": "/api/v1/parcels/estimate",
            "fieldErrors": {"weightKg": "Either weightKg, dimensions, or parcelSize must be provided."},
            "invalidFields": {"weightKg": "Either weightKg, dimensions, or parcelSize must be provided."}}},
        "unauthorized": {"summary": "Missing or invalid bearer token", "value": {
            "type": "about:blank", "title": "Unauthorized", "status": 401,
            "detail": "Authentication is required or the bearer token is invalid.", "instance": "/api/v1/parcels"}},
        "forbidden": {"summary": "Authenticated caller lacks the required role", "value": {
            "type": "about:blank", "title": "Forbidden", "status": 403,
            "detail": "You do not have access to this resource.", "instance": "/api/v1/parcels"}},
        "notFound": {"summary": "Resource not found", "value": {
            "type": "about:blank", "title": "Not Found", "status": 404,
            "detail": "Order not found.", "instance": "/api/v1/merchant/orders/00000000-0000-4000-8000-000000000001"}},
    }}}}

SCHEMAS = {
    "common": {"Problem": PROBLEM, "StringMap": {"type": "object", "additionalProperties": STR}},
    "identity": {
        "UserProfile": obj({"id": UUID, "email": STR, "name": NULLSTR, "phone": NULLSTR, "enabled": BOOL,
                             "accountStatus": STR, "merchantStatus": NULLSTR, "roles": arr(STR)}),
        "UpdateUserProfileRequest": obj({"name": {"type": ["string", "null"], "maxLength": 200},
                                          "phone": {"type": ["string", "null"], "maxLength": 32}}),
        "AdminUpdateUserRequest": obj({"name": {"type": ["string", "null"], "maxLength": 200},
                                         "phone": {"type": ["string", "null"], "maxLength": 32},
                                         "accountStatus": {"type": ["string", "null"], "pattern": r"(?i)^\s*(ACTIVE|DISABLED|SUSPENDED)\s*$"},
                                         "reason": {"type": ["string", "null"], "maxLength": 500}}, additionalProperties=False),
        "MerchantApplication": obj({"id": UUID, "appUserId": UUID, "businessName": STR, "businessRegNo": NULLSTR,
                                     "contactPhone": STR, "status": STR, "rejectionReason": NULLSTR,
                                     "createdAt": DATE, "reviewedAt": {"type": ["string", "null"], "format": "date-time"}}),
        "CreateMerchantApplicationRequest": obj({"businessName": field(minLength=1, maxLength=140),
                                                 "businessRegNo": {"type": ["string", "null"], "maxLength": 80},
                                                 "contactPhone": field(pattern=r"\+?[0-9]{9,15}")}, ("businessName", "contactPhone")),
        "ReviewMerchantApplicationRequest": obj({"decision": field(enum=["APPROVED", "REJECTED"]),
                                                 "rejectionReason": {"type": ["string", "null"], "maxLength": 1000}}, ("decision",)),
        "SuspendMerchantRequest": obj({"reason": field(minLength=1, maxLength=1000)}, ("reason",)),
        "MerchantApiKeyMetadata": obj({"id": UUID, "prefix": STR, "createdAt": DATE,
                                        "rotatedAt": {"type": ["string", "null"], "format": "date-time"},
                                        "revokedAt": {"type": ["string", "null"], "format": "date-time"}, "active": BOOL}),
        "IssuedMerchantApiKey": obj({"secret": STR, "credential": {"$ref": "#/components/schemas/MerchantApiKeyMetadata"}}, ("secret", "credential")),
        "CourierProfile": obj({"id": UUID, "userId": UUID, "email": STR, "name": STR, "phone": STR, "active": BOOL,
                                "accountStatus": STR, "assignedZone": NULLSTR, "vehicleType": NULLSTR,
                                "createdAt": DATE, "updatedAt": DATE}),
        "UpdateCourierProfileRequest": obj({"active": {"type": ["boolean", "null"]}, "assignedZone": {"type": ["string", "null"], "maxLength": 64},
                                             "vehicleType": {"type": ["string", "null"], "enum": ["BICYCLE", "MOTORCYCLE", "CAR", "VAN", "TRUCK", None]},
                                             "name": {"type": ["string", "null"], "maxLength": 200},
                                             "phone": {"type": ["string", "null"], "maxLength": 32}}),
    },
    "parcel": {
        "CreateParcelBookingRequest": BOOKING_REQUEST, "ParcelBookingResponse": BOOKING_RESPONSE,
        "CreateMerchantOrderRequest": obj({"externalOrderRef": field(minLength=1, maxLength=100), "sender": CONTACT,
            "recipient": CONTACT, "parcel": PARCEL, "destinationType": {"type": ["string", "null"], "enum": ["SAME_CITY", "OTHER_DISTRICT", None]},
            "isExpress": {"type": ["boolean", "null"]}, "notes": {"type": ["string", "null"], "maxLength": 500}},
            ("externalOrderRef", "sender", "recipient", "parcel")),
        "MerchantOrderResponse": obj({"id": UUID, "trackingRef": STR, "externalOrderRef": STR, "merchantId": STR, "status": STR,
            "fee": DEC, "currency": STR, "estimatedDays": INT, "createdAt": DATE, "sender": CONTACT, "recipient": CONTACT, "parcel": PARCEL}),
        "UpdateParcelStatusRequest": obj({"status": field(enum=["CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"]),
            "reason": {"type": ["string", "null"], "maxLength": 200}, "note": {"type": ["string", "null"], "maxLength": 500}}, ("status",)),
        "ParcelStatusResponse": obj({"parcelId": UUID, "trackingRef": STR, "previousStatus": STR, "status": STR,
            "changed": BOOL, "changedAt": {"type": ["string", "null"], "format": "date-time"}}),
        "FeeEstimateRequest": obj({"pickupCity": {"type": ["string", "null"], "maxLength": 100}, "deliveryCity": {"type": ["string", "null"], "maxLength": 100},
            "destinationType": {"type": ["string", "null"], "enum": ["SAME_CITY", "OTHER_DISTRICT", None]},
            "parcelSize": {"type": ["string", "null"], "enum": ["SMALL", "MEDIUM", "LARGE", None]},
            "weightKg": {"type": ["number", "null"], "minimum": 0.1, "maximum": 20, "multipleOf": 0.0001}, "parcelCategory": {"type": ["string", "null"], "maxLength": 100},
            "isExpress": {"type": ["boolean", "null"]}, "distanceKm": {"type": ["number", "null"], "minimum": 0, "maximum": 2000, "multipleOf": 0.0001},
            "lengthCm": {"type": ["number", "null"], "minimum": 0.01, "maximum": 500, "multipleOf": 0.0001}, "widthCm": {"type": ["number", "null"], "minimum": 0.01, "maximum": 500, "multipleOf": 0.0001},
            "heightCm": {"type": ["number", "null"], "minimum": 0.01, "maximum": 500, "multipleOf": 0.0001}, "originZone": {"type": ["string", "null"], "maxLength": 64},
            "destinationZone": {"type": ["string", "null"], "maxLength": 64}, "currency": {"type": ["string", "null"], "maxLength": 10}}),
        "FeeEstimateResponse": obj({"fee": DEC, "formattedFee": STR, "currency": STR, "estimatedDays": INT,
                                     "breakdown": obj({"baseFee": DEC, "weightFee": DEC, "distanceSurcharge": DEC, "expressFee": DEC})}),
        "PricingRule": obj({"id": UUID, "ruleName": STR, "baseFee": DEC, "includedWeightKg": DEC, "additionalWeightRatePerKg": DEC,
                            "distanceSurchargeOtherDistrict": DEC, "expressSurcharge": DEC, "perKmRate": DEC, "currency": STR, "active": BOOL, "updatedAt": DATE}),
        "PricingRuleUpdateRequest": obj({"baseFee": field("number", minimum=0), "includedWeightKg": field("number", minimum=0),
            "additionalWeightRatePerKg": field("number", minimum=0), "distanceSurchargeOtherDistrict": field("number", minimum=0),
            "expressSurcharge": field("number", minimum=0), "perKmRate": field("number", minimum=0), "currency": {"type": ["string", "null"]}},
            ("baseFee", "includedWeightKg", "additionalWeightRatePerKg", "distanceSurchargeOtherDistrict", "expressSurcharge", "perKmRate")),
        "DeliveryZone": obj({"id": UUID, "code": STR, "name": STR, "description": STR, "active": BOOL}),
    },
    "dispatch": {
        "AssignRequest": obj({"parcelId": UUID, "courierId": UUID}, ("parcelId", "courierId")),
        "ReassignRequest": obj({"courierId": UUID}, ("courierId",)),
        "Assignment": obj({"parcelId": UUID, "courierId": UUID, "courierUserId": UUID, "assignedBy": STR, "assignedAt": DATE}),
        "DispatchParcel": obj({"parcelId": UUID, "trackingReference": STR, "pickupSummary": STR, "dropoffSummary": STR, "createdAt": DATE}),
        "DispatchQueue": obj({"items": arr({"$ref": "#/components/schemas/DispatchParcel"}), "total": {"type": ["integer", "null"]},
                               "matchingTotal": {"type": ["integer", "null"]}, "complete": BOOL}),
        "Courier": obj({"id": UUID, "userId": UUID, "email": STR, "name": STR, "phone": STR, "active": BOOL, "accountStatus": STR}),
        "CourierWorkload": obj({"courierId": UUID, "userId": UUID, "name": NULLSTR, "active": BOOL, "accountStatus": STR,
                                "assignmentCount": field("integer", format="int64", minimum=0, description="Lifetime S3 assignment rows for this courier; includes completed proofs, with no date or parcel-status filter."),
                                "availability": field(enum=["AVAILABLE", "UNAVAILABLE"], description="AVAILABLE only when the S1 courier is active and accountStatus is ACTIVE; not a capacity metric.")}),
        "Workload": obj({"items": arr({"$ref": "#/components/schemas/CourierWorkload"}),
                         "total": field("integer", format="int64", minimum=0, description="Courier rows after search and availability filtering, not a delivery total."),
                         "overloadConfigured": field("boolean", const=False, description="No capacity threshold is configured.")}),
        "DeliverySummary": obj({"parcelId": UUID, "courierId": UUID, "assignedAt": DATE, "proofId": {"anyOf": [UUID, {"type": "null"}]}, "completionState": NULLSTR}),
        "SubmitProofRequest": obj({"recipientName": {"type": ["string", "null"], "maxLength": 100}, "notes": {"type": ["string", "null"], "maxLength": 500},
                                    "proofReference": {"type": ["string", "null"], "maxLength": 200}}, ("recipientName", "proofReference")),
        "ProofResponse": obj({"proofId": UUID, "parcelId": UUID, "recipientName": STR, "notes": NULLSTR, "proofReference": STR,
                              "submittedAt": DATE, "completionState": STR, "completedAt": {"type": ["string", "null"], "format": "date-time"}}),
    },
    "tracking": {
        "DailyDeliveryRow": obj({"day": field(format="date"), "created": field("integer", minimum=0),
            "delivered": field("integer", minimum=0), "failed": field("integer", minimum=0),
            "active": field("integer", minimum=0, description="Known-created parcels not terminal by this day's end, from the existing metrics view."),
            "successRate": {"type": ["number", "null"], "minimum": 0, "maximum": 1,
                            "description": "delivered / (delivered + failed); null when the denominator is zero."}},
            ("day", "created", "delivered", "failed", "active", "successRate")),
        "DailyDeliveryReport": obj({"from": field(format="date"), "to": field(format="date"),
            "timezone": field(enum=["Asia/Colombo"]),
            "days": {"type": "array", "minItems": 1, "maxItems": 92, "items": {"$ref": "#/components/schemas/DailyDeliveryRow"}}},
            ("from", "to", "timezone", "days")),
        "DeliverySummaryReport": obj({"from": field(format="date"), "to": field(format="date"),
            "timezone": field(enum=["Asia/Colombo"]), "created": field("integer", minimum=0),
            "delivered": field("integer", minimum=0), "failed": field("integer", minimum=0),
            "successRate": {"type": ["number", "null"], "minimum": 0, "maximum": 1,
                            "description": "Total delivered / (total delivered + total failed); null for a zero denominator."}},
            ("from", "to", "timezone", "created", "delivered", "failed", "successRate")),
        "Party": obj({"name": STR, "phone": STR, "address": STR, "city": STR}),
        "ParcelDetails": obj({"description": STR, "weightKg": DEC, "category": STR, "size": STR}),
        "TrackingEvent": obj({"id": UUID, "trackingRef": STR, "status": STR, "title": STR, "description": STR, "actorRole": STR,
                              "location": STR, "occurredAt": DATE, "failureReason": NULLSTR}),
        "TrackingEventStream": STR,
        "TrackingMilestone": obj({"id": STR, "title": STR, "description": STR, "timestamp": DATE, "status": STR, "location": STR, "failureReason": NULLSTR}),
        "PublicTracking": obj({"trackingRef": STR, "status": STR, "rawStatus": STR, "category": STR, "size": STR, "description": STR,
            "originCity": STR, "destinationCity": STR, "estimatedDays": INT, "estimatedDeliveryDate": DATE, "createdAt": DATE, "deliveredAt": DATE,
            "failureReason": NULLSTR, "events": arr({"$ref": "#/components/schemas/TrackingEvent"}), "timeline": arr({"$ref": "#/components/schemas/TrackingMilestone"})}),
        "CustomerDelivery": obj({"id": STR, "trackingRef": STR, "status": STR, "rawStatus": STR, "sender": {"$ref": "#/components/schemas/Party"},
            "recipient": {"$ref": "#/components/schemas/Party"}, "parcel": {"$ref": "#/components/schemas/ParcelDetails"}, "fee": DEC, "currency": STR,
            "formattedFee": STR, "estimatedDays": INT, "createdAt": DATE, "formattedCreatedAt": STR, "deliveredAt": DATE,
            "formattedDeliveredAt": STR, "failureReason": NULLSTR, "timeline": arr({"$ref": "#/components/schemas/TrackingMilestone"})}),
        "CustomerDeliveryList": obj({"content": arr({"$ref": "#/components/schemas/CustomerDelivery"}), "parcels": arr({"$ref": "#/components/schemas/CustomerDelivery"}),
            "totalElements": INT, "total": INT, "totalPages": INT, "page": INT, "size": INT, "totalSpent": DEC, "formattedTotalSpent": STR}),
    },
    "notifications": {
        "AttemptHistory": obj({"notifications": arr({"$ref": "#/components/schemas/NotificationAttempt"}), "webhooks": arr({"$ref": "#/components/schemas/WebhookAttempt"}),
            "page": INT, "size": INT, "totalNotifications": INT, "totalWebhooks": INT}),
        "NotificationAttempt": obj({"id": UUID, "eventId": UUID, "parcelId": UUID, "eventType": STR, "trackingRef": STR, "parcelStatus": STR,
            "channel": STR, "status": STR, "attempts": INT, "lastError": NULLSTR, "nextAttemptAt": DATE, "createdAt": DATE, "updatedAt": DATE}),
        "WebhookAttempt": obj({"id": UUID, "eventId": UUID, "parcelId": UUID, "eventType": STR, "status": STR, "attempts": INT,
            "responseStatus": {"type": ["integer", "null"]}, "lastError": NULLSTR, "nextAttemptAt": DATE, "createdAt": DATE, "updatedAt": DATE}),
        "RegisterWebhookRequest": obj({"url": field(minLength=1, maxLength=2048,
            description="HTTPS endpoint URL; public addresses only. Loopback, private, link-local, multicast and reserved addresses are rejected.")}, ("url",)),
        "WebhookSecretResponse": obj({"id": UUID, "url": STR, "active": BOOL, "secretPrefix": STR, "createdAt": DATE, "updatedAt": DATE, "secret": STR}),
        "WebhookSubscription": obj({"id": UUID, "url": STR, "active": BOOL, "secretPrefix": STR, "createdAt": DATE, "updatedAt": DATE}),
        "WebhookDelivery": obj({"id": UUID, "eventId": UUID, "eventType": STR, "parcelId": UUID, "status": STR, "attempts": INT,
            "responseStatus": {"type": ["integer", "null"]}, "lastError": NULLSTR, "nextAttemptAt": DATE, "createdAt": DATE, "updatedAt": DATE}),
        "WebhookDeliveryPage": obj({"items": arr({"$ref": "#/components/schemas/WebhookDelivery"}), "page": INT, "size": INT, "total": INT}),
    },
}

# Each record: method, path, operationId, summary, audience, success status, response schema,
# description, query params, request schema, request headers, error statuses, response headers.
OPS = {
"identity": [
    ("get","/api/v1/users/me","getMyUserProfile","Read the authenticated user's profile","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"UserProfile","Returns the profile for the token subject; no user id is accepted.",[],None,[],[401,403,503],[]),
    ("patch","/api/v1/users/me","patchMyUserProfile","Update the authenticated user's profile","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"UserProfile","Only name and phone are mutable; omitted values remain unchanged.",[],"UpdateUserProfileRequest",[],[400,401,403,422,503],[]),
    ("get","/api/v1/users/{userId}","getUserProfileById","Read a user profile","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"UserProfile","The subject may read itself; DISPATCHER may read another user.",[],None,[],[400,401,403,404,503],[]),
    ("patch","/api/v1/users/{userId}","patchUserProfileById","Update a user profile","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"UserProfile","The subject may update itself; DISPATCHER may update another user with administrative audit rules.",[],"UpdateUserProfileRequest",[],[400,401,403,404,422,503],[]),
    ("get","/api/v1/users/courier-profile","getCourierProfileAlias","Read the authenticated courier profile","COURIER",200,"CourierProfile","Internal S1 identity route used by S3; the profile is resolved from the bearer subject.",[],None,[],[401,403,404,503],[]),
    ("post","/api/v1/users/courier-profile/provision","provisionCourierProfileAlias","Provision the authenticated courier profile","COURIER",200,"CourierProfile","Internal S1 route used by S3; provisions only the courier represented by the bearer subject.",[],None,[],[401,403,409,503],[]),
    ("get","/api/v1/courier/profile","getCourierProfile","Read the authenticated courier profile","COURIER",200,"CourierProfile","Returns only the profile resolved from the bearer subject.",[],None,[],[401,403,404,503],[]),
    ("post","/api/v1/courier/profile/provision","provisionCourierProfile","Provision the authenticated courier profile","COURIER",200,"CourierProfile","Provisions only the courier represented by the bearer subject.",[],None,[],[401,403,409,503],[]),
    ("get","/api/v1/merchants/me","getMyMerchantApplication","Read the caller's merchant application","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"MerchantApplication","Returns the application associated with the token subject.",[],None,[],[401,403,404,503],[]),
    ("post","/api/v1/merchants","submitMerchantApplication","Submit or reapply for merchant status","CUSTOMER",200,"MerchantApplication","Applicant identity comes from the JWT subject, not the request body.",[],"CreateMerchantApplicationRequest",[],[400,401,403,409,422,503],[]),
    ("get","/api/v1/merchants/api-key","getMyMerchantApiKey","Read merchant API-key metadata","MERCHANT",200,"MerchantApiKeyMetadata","Returns metadata only; the secret is not returned by GET. Approved and active merchants only.",[],None,[],[401,403,503],[]),
    ("post","/api/v1/merchants/api-key","issueMerchantApiKey","Issue or rotate a merchant API key","MERCHANT",200,"IssuedMerchantApiKey","The plaintext key is returned once in this response. Response includes Cache-Control: no-store and Pragma: no-cache.",[],None,[],[401,403,503],["Cache-Control","Pragma"]),
    ("delete","/api/v1/merchants/api-key","revokeMerchantApiKey","Revoke the merchant API key","MERCHANT",200,"MerchantApiKeyMetadata","Returns metadata for the caller's key; null when no key exists. Approved and active merchants only.",[],None,[],[401,403,503],[]),
    ("get","/api/v1/admin/merchants","listMerchantApplications","List pending merchant applications","DISPATCHER",200,"MerchantApplicationList","Dispatcher-only review queue.",[],None,[],[401,403,503],[]),
    ("patch","/api/v1/admin/merchants/{applicationId}","reviewMerchantApplication","Approve or reject a merchant application","DISPATCHER",200,"MerchantApplication","Dispatcher decision; rejectionReason is required for REJECTED and must be absent for APPROVED.",[],"ReviewMerchantApplicationRequest",[],[400,401,403,404,409,422,503],[]),
    ("patch","/api/v1/admin/merchants/{applicationId}/suspend","suspendMerchantApplication","Suspend an approved merchant","DISPATCHER",200,"MerchantApplication","Suspends an approved merchant application. Requires a non-blank suspension reason.",[],"SuspendMerchantRequest",[],[400,401,403,404,409,422,503],[]),
    ("patch","/api/v1/admin/merchants/{applicationId}/reinstate","reinstateMerchantApplication","Reinstate a suspended merchant","DISPATCHER",200,"MerchantApplication","Reinstates a suspended merchant application to approved status.",[],None,[],[400,401,403,404,409,503],[]),
    ("get","/api/v1/admin/merchants/{applicationId}/api-key","getAdminMerchantApiKey","Read merchant API-key metadata (Dispatcher)","DISPATCHER",200,"MerchantApiKeyMetadata","Returns the API key metadata for a merchant application. Dispatcher read.",[],None,[],[400,401,403,404,503],[]),
    ("delete","/api/v1/admin/merchants/{applicationId}/api-key","revokeAdminMerchantApiKey","Revoke a merchant API key (Dispatcher)","DISPATCHER",200,"MerchantApiKeyMetadata","Revokes the API key for the specified merchant application.",[],None,[],[400,401,403,404,503],[]),
    ("get","/api/v1/admin/users","listUsers","List users","DISPATCHER",200,"UserProfileList","Optional status and text filters.",[("status","string",False,None,None,None),("search","string",False,None,None,None)],None,[],[400,401,403,503],[]),
    ("get","/api/v1/admin/users/{userId}","getAdminUser","Read a user by id","DISPATCHER",200,"UserProfile","Dispatcher-only user lookup.",[],None,[],[400,401,403,404,503],[]),
    ("patch","/api/v1/admin/users/{userId}","patchAdminUser","Update user profile or account status","DISPATCHER",200,"UserProfile","Only name, phone, accountStatus and reason are accepted. Roles, email and identity fields are rejected.",[],"AdminUpdateUserRequest",[],[400,401,403,404,422,503],[]),
    ("get","/api/v1/admin/couriers","listCouriers","List courier profiles","DISPATCHER",200,"CourierProfileList","Optional status, activity, zone and search filters.",[("status","string",False,None,None,None),("active","boolean",False,None,None,None),("zone","string",False,None,None,None),("search","string",False,None,None,None)],None,[],[401,403,503],[]),
    ("get","/api/v1/admin/couriers/{courierId}","getAdminCourier","Read a courier profile","DISPATCHER",200,"CourierProfile","Dispatcher-only courier profile lookup.",[],None,[],[400,401,403,404,503],[]),
    ("patch","/api/v1/admin/couriers/{courierId}","patchAdminCourier","Update a courier profile","DISPATCHER",200,"CourierProfile","Updates the courier fields accepted by S1.",[],"UpdateCourierProfileRequest",[],[400,401,403,404,422,503],[]),
],
"parcel": [
    ("post","/api/v1/parcels","createParcelBooking","Create a parcel booking","CUSTOMER",201,"ParcelBookingResponse","Idempotent replay returns 200; a new booking returns 201 and Location. Idempotency-Key is optional, 1–100 characters.",[],"CreateParcelBookingRequest",[("Idempotency-Key","string",False,1,100,None)],[400,401,403,409,422,503],["Location"]),
    ("get","/api/v1/parcels/{id}","getParcelBooking","Retrieve a parcel booking by ID","CUSTOMER, DISPATCHER",200,"ParcelBookingResponse","CUSTOMER may only retrieve their own parcel (scoped by token subject; foreign parcel returns 404). DISPATCHER may retrieve any parcel via internal routing.",[],None,[],[400,401,403,404,503],[]),
    ("post","/api/v1/parcels/estimate","estimateParcelFee","Estimate a parcel fee","CUSTOMER, DISPATCHER",200,"FeeEstimateResponse","Read-only quote. Validation errors are 400 with both fieldErrors and invalidFields.",[],"FeeEstimateRequest",[],[400,401,403,503],[]),
    ("post","/api/v1/parcels/estimate-fee","estimateParcelFeeAlias","Estimate a parcel fee (alias)","CUSTOMER, DISPATCHER",200,"FeeEstimateResponse","Alias of /api/v1/parcels/estimate with identical behavior.",[],"FeeEstimateRequest",[],[400,401,403,503],[]),
    ("patch","/api/v1/parcels/{parcelId}/status","updateParcelStatus","Update parcel status","DISPATCHER",200,"ParcelStatusResponse","DISPATCHER through gateway. Internal direct S2 calls may use SERVICE; SERVICE is not admitted by the gateway. Repeating current status is 200 with changed=false.",[],"UpdateParcelStatusRequest",[],[400,401,403,404,409,422,503],[]),
    ("get","/api/v1/zones","listDeliveryZones","List active delivery zones","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"DeliveryZoneList","Authenticated human roles only.",[],None,[],[401,403,503],[]),
    ("get","/api/v1/zones/{code}","getDeliveryZoneByCode","Get delivery zone by code","CUSTOMER, MERCHANT, COURIER, DISPATCHER",200,"DeliveryZone","Returns delivery zone details for the given zone code.",[],None,[],[400,401,403,404,503],[]),
    ("get","/api/v1/admin/pricing-rules","getPricingRule","Read the active pricing rule","DISPATCHER",200,"PricingRule","Dispatcher-only pricing administration.",[],None,[],[401,403,503],[]),
    ("put","/api/v1/admin/pricing-rules","updatePricingRule","Update the standard pricing rule","DISPATCHER",200,"PricingRule","Six monetary values are required and nonnegative; optional currency is passed through.",[],"PricingRuleUpdateRequest",[],[400,401,403,503],[]),
    ("post","/api/v1/merchant/orders","createMerchantOrder","Create or replay a merchant order","MERCHANT",201,"MerchantOrderResponse","New order returns 201 with Location; matching externalOrderRef replay returns 200. Merchant identity is the token subject.",[],"CreateMerchantOrderRequest",[],[400,401,403,404,409,422,503],["Location"]),
    ("get","/api/v1/merchant/orders","listMerchantOrders","List the merchant's orders","MERCHANT",200,"MerchantOrderList","limit defaults to 50 and is clamped to 1–100; results are scoped to token subject.",[("limit","integer",False,None,None,50)],None,[],[400,401,403,503],[]),
    ("get","/api/v1/merchant/orders/{id}","getMerchantOrder","Read one merchant order","MERCHANT",200,"MerchantOrderResponse","Order lookup is scoped to token subject; another merchant's order returns 404.",[],None,[],[400,401,403,404,503],[]),
],
"dispatch": [
    ("get","/api/v1/dispatch/queue","getDispatchQueue","List unassigned parcels","DISPATCHER",200,"DispatchQueue","Queue snapshot may be incomplete when its source cannot fully reconcile.",[("search","string",False,0,200,""),("from","string",False,None,None,None),("to","string",False,None,None,None),("sort","string",False,None,None,"newest")],None,[],[400,401,403,503],[]),
    ("get","/api/v1/dispatch/unassigned","getUnassignedQueueAlias","List unassigned parcels (alias)","DISPATCHER",200,"DispatchQueue","Alias of /api/v1/dispatch/queue.",[("search","string",False,0,200,""),("from","string",False,None,None,None),("to","string",False,None,None,None),("sort","string",False,None,None,"newest")],None,[],[400,401,403,503],[]),
    ("get","/api/v1/dispatch/eligible-couriers","listEligibleCouriers","List eligible couriers for a parcel","DISPATCHER",200,"CourierList","Requires parcelId; S3 verifies parcel eligibility through its configured parcel-source integration.",[("parcelId","string",True,None,None,None)],None,[],[400,401,403,404,409,503],[]),
    ("get","/api/v1/dispatch/workload","getCourierWorkload","Read courier workload","DISPATCHER",200,"Workload","Optional name search and availability filter.",[("search","string",False,0,200,""),("availability","string",False,None,None,"")],None,[],[400,401,403,503],[]),
    ("post","/api/v1/dispatch/assignments","assignCourier","Assign a parcel to a courier","DISPATCHER",201,"Assignment","Actor is read from the JWT subject. An existing parcel assignment conflicts.",[],"AssignRequest",[],[400,401,403,404,409,422,503],[]),
    ("post","/api/v1/dispatch/assignments/{parcelId}/reassign","reassignCourier","Reassign an assigned parcel to an eligible courier","DISPATCHER",200,"Assignment","Actor is read from the JWT subject. The prior and new courier IDs are recorded in S3 audit and outbox records.",[],"ReassignRequest",[],[400,401,403,404,409,503],[]),
    ("delete","/api/v1/dispatch/assignments/{parcelId}","cancelAssignment","Cancel an active parcel assignment","DISPATCHER",204,None,"Actor is read from the JWT subject. Cancellation writes an audit and outbox record atomically.",[] ,None,[],[401,403,404,503],[]),
    ("get","/api/v1/courier/deliveries","listCourierDeliveries","List the authenticated courier's assignments","COURIER",200,"DeliverySummaryList","Only assignments belonging to the token courier are returned.",[],None,[],[401,403,503],[]),
    ("get","/api/v1/courier/deliveries/{parcelId}","getCourierDelivery","Read an assigned delivery","COURIER",200,"DeliverySummary","Both courier profile id and user id must match the authenticated courier.",[],None,[],[400,401,403,404,503],[]),
    ("get","/api/v1/courier/deliveries/{parcelId}/proof","getDeliveryProof","Read proof of delivery","COURIER",200,"ProofResponse","Only the courier assigned to the parcel can read proof. Cache-Control is no-store.",[],None,[],[400,401,403,404,503],["Cache-Control"]),
    ("post","/api/v1/courier/deliveries/{parcelId}/proof","submitDeliveryProof","Submit proof of delivery","COURIER",201,"ProofResponse","New proof returns 201; same evidence replay returns 200; conflicting evidence returns 409. Cache-Control is no-store.",[],"SubmitProofRequest",[],[400,401,403,404,409,503],["Cache-Control"]),
],
"tracking": [
    ("get","/api/v1/reports/deliveries/daily","getDailyDeliveryReport","Read daily delivery metrics","DISPATCHER",200,"DailyDeliveryReport","Required inclusive Asia/Colombo dates, from <= to, at most 92 days, to no later than today. Uses business event occurred_at via tracking.reporting_day(). Every requested day is returned in ascending order. Existing view values, including active on intervening days, are preserved; absent view days have zero counts and null successRate. Invalid dates/ranges return 400 with fieldErrors naming from or to. Date filters only; CSV export deferred.",[("from","string",True,None,None,None),("to","string",True,None,None,None)],None,[],[400,401,403],[]),
    ("get","/api/v1/reports/deliveries/summary","getDeliverySummaryReport","Read delivery metric totals","DISPATCHER",200,"DeliverySummaryReport","Required inclusive Asia/Colombo dates, from <= to, at most 92 days, to no later than today. Totals equal daily row sums; successRate is the ratio of total delivered to total delivered plus failed, not an average of daily rates. Zero denominator returns null. Invalid dates/ranges return 400 with fieldErrors naming from or to. Date filters only; CSV export deferred.",[("from","string",True,None,None,None),("to","string",True,None,None,None)],None,[],[400,401,403],[]),
    ("get","/api/v1/tracking/mine","getMyTrackingHistory","List the caller's delivery history","CUSTOMER, MERCHANT",200,"CustomerDeliveryList","Owner is the bearer token subject. Optional page status and search filters; effective page size is clamped to 1–100.",[("page","integer",False,1,None,1),("size","integer",False,None,None,None),("limit","integer",False,None,None,None),("status","string",False,None,None,None),("q","string",False,None,None,None),("search","string",False,None,None,None)],None,[],[400,401,403,503],[]),
    ("get","/api/v1/tracking/{trackingRef}","getPublicTracking","Read public parcel tracking","PUBLIC",200,"PublicTracking","Anonymous and human callers are allowed; SERVICE-only callers are rejected. Response omits names, phones, full street addresses and fees.",[],None,[],[400,403,404,429,503],[]),
    ("get","/api/v1/tracking/{trackingRef}/events","getPublicTrackingEvents","Read tracking events or subscribe via SSE","PUBLIC",200,"TrackingEventList","JSON by default; Accept: text/event-stream switches this same endpoint to SSE. SERVICE-only callers are rejected.",[],None,[],[400,403,404,429,503],[]),
    ("get","/api/v1/tracking/{trackingRef}/stream","streamPublicTracking","Subscribe to tracking events","PUBLIC",200,"TrackingEventStream","Server-sent events; gateway sets Cache-Control: no-cache and X-Accel-Buffering: no.",[],None,[],[400,403,404,429,503],["Cache-Control","X-Accel-Buffering"]),
],
"notifications": [
    ("get","/api/v1/admin/notification-attempts","listNotificationAttempts","List notification and webhook outcomes","DISPATCHER",200,"AttemptHistory","Dispatcher-only operational history; response excludes message bodies and webhook payloads.",[("page","integer",False,0,None,0),("size","integer",False,1,100,25)],None,[],[400,401,403,503],[]),
    ("get","/api/v1/admin/webhook-deliveries","listWebhookDeliveryAttempts","List notification and webhook outcomes","DISPATCHER",200,"AttemptHistory","Dispatcher-only operational history; response excludes message bodies and webhook payloads.",[("page","integer",False,0,None,0),("size","integer",False,1,100,25)],None,[],[400,401,403,503],[]),
    ("post","/api/v1/admin/webhook-deliveries/{deliveryId}/replay","replayWebhookDelivery","Replay a failed webhook delivery","DISPATCHER",204,None,"Replays a failed webhook delivery for an active subscription.",[],None,[],[400,401,403,404,409,503],[]),
    ("post","/api/v1/merchant/webhooks","registerMerchantWebhook","Register or replace the merchant webhook","MERCHANT",201,"WebhookSecretResponse","New subscription returns 201; replacement returns 200. Signing secret is shown once and response is Cache-Control: no-store.",[],"RegisterWebhookRequest",[],[400,401,403,404,415,422,503],["Location","Cache-Control"]),
    ("get","/api/v1/merchant/webhooks","getMerchantWebhook","Read the merchant webhook configuration","MERCHANT",200,"WebhookSubscription","Never returns the signing secret.",[],None,[],[401,403,404,503],[]),
    ("delete","/api/v1/merchant/webhooks","disableMerchantWebhook","Disable the merchant webhook","MERCHANT",204,None,"Disables the token subject's webhook subscription.",[],None,[],[401,403,404,503],[]),
    ("post","/api/v1/merchant/webhooks/secret","rotateMerchantWebhookSecret","Rotate the webhook signing secret","MERCHANT",200,"WebhookSecretResponse","New signing secret is shown once; response is Cache-Control: no-store.",[],None,[],[401,403,404,503],["Cache-Control"]),
    ("get","/api/v1/merchant/webhooks/deliveries","listMerchantWebhookDeliveries","List the merchant's webhook deliveries","MERCHANT",200,"WebhookDeliveryPage","Results are scoped to the merchant subject; payload is not returned.",[("page","integer",False,0,None,0),("size","integer",False,1,100,20)],None,[],[400,401,403,503],[]),
],
}

SERVICE_INFO = {
    "identity": ("S1 Identity & Partner Service", "S1", "Identity, partner applications, courier profiles and merchant API credentials."),
    "parcel": ("S2 Parcel & Pricing Service", "S2", "Parcel booking, pricing, merchant orders, delivery zones and parcel status."),
    "dispatch": ("S3 Dispatch & Courier Service", "S3", "Dispatcher assignment and courier proof-of-delivery operations."),
    "tracking": ("S4 Tracking & Reporting Service", "S4", "Public parcel tracking and authenticated tracking history."),
    "notifications": ("S5 Notification & Integration Service", "S5", "Merchant webhook configuration and dispatcher integration outcome history."),
}


def schema_ref(name):
    return {"$ref": f"#/components/schemas/{name}"}


def error_response(code):
    return {"description": {
        400: "Malformed request or invalid parameter", 401: "Missing or invalid bearer token",
        403: "Authenticated caller lacks an allowed role or resource permission", 404: "Resource not found",
        409: "Request conflicts with current resource state", 415: "Unsupported media type",
        422: "Request validation failed", 429: "Gateway rate limit exceeded",
        500: "Unexpected server error", 502: "Gateway received an invalid upstream response",
        503: "Service or required dependency is unavailable", 504: "Gateway upstream timed out",
    }[code], "content": {"application/problem+json": {"schema": schema_ref("Problem")}}}


def make_operation(service, row):
    method, path, opid, summary, roles, success, response_schema, description, query, body, headers, errors, response_headers = row
    params = []
    for name in re.findall(r"\{([^}]+)\}", path):
        p_schema = {"type": "string", "format": "uuid" if name.lower().endswith("id") else "string"}
        if name == "trackingRef": p_schema.update({"minLength": 6, "maxLength": 32, "pattern": r"^[A-Za-z0-9_-]{6,32}$"})
        params.append({"name": name, "in": "path", "required": True, "description": f"{name} identifier or tracking reference.",
                       "schema": p_schema,
                       "example": "00000000-0000-4000-8000-000000000001" if name.lower().endswith("id") else "ZT-ABCDEFGH12"})
    for name, typ, required, minimum, maximum, default in query:
        schema = {"type": typ}
        if minimum is not None: schema["minimum" if typ == "integer" else "minLength"] = minimum
        if maximum is not None: schema["maximum" if typ == "integer" else "maxLength"] = maximum
        if default is not None: schema["default"] = default
        if name in ("from", "to"): schema = {"type": "string", "format": "date"}
        if name == "availability": schema["enum"] = ["", "available", "unavailable"]
        if name == "sort": schema["enum"] = ["newest", "oldest"]
        params.append({"name": name, "in": "query", "required": required,
                       "description": f"{name} filter or pagination parameter.", "schema": schema})
    params.append({"name": "X-Correlation-Id", "in": "header", "required": False,
        "description": "Optional correlation identifier: 1-128 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit. Missing, duplicate or invalid values are replaced with a UUID; the accepted ID is returned in the response header.",
        "schema": CORRELATION_SCHEMA, "example": "request-123"})
    for name, typ, required, minimum, maximum, _ in headers:
        params.append({"name": name, "in": "header", "required": required,
            "description": "Optional booking idempotency key; blank values are rejected.",
            "schema": {"type": typ, **({"minLength": minimum} if minimum else {}), **({"maxLength": maximum} if maximum else {})},
            "example": "booking-2026-001"})
    responses = {}
    resp = {"description": "Success"}
    if response_schema:
        if path.endswith("/stream"):
            resp["content"] = {"text/event-stream": {"schema": schema_ref("TrackingEventStream")}}
        elif path.endswith("/events") and service == "tracking":
            resp["content"] = {"application/json": {"schema": schema_ref("TrackingEventList")},
                               "text/event-stream": {"schema": schema_ref("TrackingEventStream")}}
        else:
            resp["content"] = {"application/json": {"schema": schema_ref(response_schema)}}
    if success in (200, 201):
        resp["headers"] = {"X-Correlation-Id": {"description": "Accepted correlation identifier.", "schema": CORRELATION_SCHEMA}}
        for h in response_headers:
            resp["headers"][h] = {"description": "Response header emitted by the operation.", "schema": {"type": "string"}}
            if h == "Cache-Control": resp["headers"][h]["example"] = "no-store"
            if h == "Pragma": resp["headers"][h]["example"] = "no-cache"
            if h == "Location": resp["headers"][h]["example"] = path.replace("{id}", "00000000-0000-4000-8000-000000000001")
    responses[str(success)] = resp
    if success == 201 and 200 not in (success,):
        # Conditional idempotency/replacement responses where implemented.
        if path in ("/api/v1/parcels", "/api/v1/merchant/orders", "/api/v1/merchant/webhooks"):
            responses["200"] = {"description": "Existing resource returned for idempotent replay or replacement.",
                                "content": {"application/json": {"schema": schema_ref(response_schema)}}}
    for code in errors:
        responses[str(code)] = error_response(code)
    # Correlation id is assigned by the gateway on both success and error responses.
    for r in responses.values():
        r.setdefault("headers", {})["X-Correlation-Id"] = {"description": "Accepted correlation identifier.", "schema": CORRELATION_SCHEMA}
    op = {"operationId": opid, "summary": summary, "description": description + (f" Allowed roles: {roles}." if roles != "PUBLIC" else " Public: anonymous and human access; SERVICE-only tokens are denied."),
          "tags": [SERVICE_INFO[service][1]], "responses": responses}
    if params: op["parameters"] = params
    if body:
        op["requestBody"] = {"required": True, "content": {"application/json": {"schema": schema_ref(body)}}}
    op["security"] = [] if roles == "PUBLIC" else [{"bearerAuth": []}]
    return op


def build(service):
    title, short, description = SERVICE_INFO[service]
    schemas = dict(SCHEMAS["common"])
    schemas.update(SCHEMAS[service])
    # Convenience collection schemas.
    for name, item in {
        "UserProfileList": "UserProfile", "MerchantApplicationList": "MerchantApplication", "CourierProfileList": "CourierProfile", "DeliveryZoneList": "DeliveryZone",
        "MerchantOrderList": "MerchantOrderResponse", "CourierList": "Courier", "DeliverySummaryList": "DeliverySummary",
        "TrackingEventList": "TrackingEvent",
    }.items():
        if item in schemas: schemas[name] = arr(schema_ref(item))
    paths = {}
    for row in OPS[service]:
        method, path = row[0], row[1]
        paths.setdefault(path, {})[method] = make_operation(service, row)
    return {"openapi": "3.1.0", "info": {"title": title + " REST API", "version": "1.0.0", "description": description + " Contract paths are gateway paths."},
            "servers": [{"url": "http://localhost:8080", "description": "API Gateway"}], "tags": [{"name": short, "description": title}],
            "paths": paths, "components": {"securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT", "description": "Keycloak JWT; required roles are stated on each operation."}}, "schemas": schemas}}


def controller_inventory():
    """Walk active @RestController mappings; handles current literal paths and aliases."""
    found = {k: set() for k in OPS}
    service_dirs = {"identity": "identity-partner-service", "parcel": "parcel-pricing-service", "dispatch": "dispatch-courier-service",
                    "tracking": "tracking-reporting-service", "notifications": "notification-integration-service"}
    for service, module in service_dirs.items():
        base = ROOT / "backend" / module / "src/main/java"
        for file in base.rglob("*Controller.java"):
            source = file.read_text()
            if "@RestController" not in source: continue
            class_start = source.find("public class")
            marker = source.find("@RequestMapping", 0, class_start if class_start >= 0 else len(source))
            mapping_arg = ""
            if marker >= 0:
                open_paren = source.find("(", marker)
                depth = 0
                quote = False
                escaped = False
                close_paren = open_paren
                for i in range(open_paren, len(source)):
                    ch = source[i]
                    if escaped:
                        escaped = False
                    elif ch == "\\" and quote:
                        escaped = True
                    elif ch == '"':
                        quote = not quote
                    elif not quote and ch == "(":
                        depth += 1
                    elif not quote and ch == ")":
                        depth -= 1
                        if depth == 0:
                            close_paren = i
                            break
                mapping_arg = source[open_paren + 1:close_paren]
            bases = re.findall(r'"([^"\n]*)"', mapping_arg) if mapping_arg else [""]
            if not bases: bases = [""]
            annotations = re.finditer(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\s*\((.*?)\))?\s*(?:@\w+(?:\([^\n]*\))?\s*)*public\s+(?:[\w<>?,. \[\]]+\s+)?\w+\s*\(", source, re.S)
            for match in annotations:
                verb = match.group(1).lower()
                arg = match.group(2) or ""
                suffixes = re.findall(r'"([^"\n]*)"', arg) or [""]
                for b in bases:
                    for suffix in suffixes:
                        path = (b.rstrip("/") + "/" + suffix.lstrip("/")).rstrip("/") or "/"
                        found[service].add((verb, path))
    return found


def gateway_inventory():
    """Read configured Gateway Path predicates and map route ids to owning modules."""
    source = (ROOT / "backend/api-gateway/src/main/resources/application.yml").read_text()
    ids = re.findall(r"^\s*- id: (\w+)\s*$", source, re.M)
    path_lines = re.findall(r"^\s*- Path=([^\n]+)", source, re.M)
    owner = {"identity": "identity", "parcel": "parcel", "dispatch": "dispatch",
             "tracking": "tracking", "notification": "notifications"}
    routes = {owner[route]: [part.strip() for part in line.split(",")]
              for route, line in zip(ids, path_lines) if route in owner}
    return routes


def gateway_matches(path, pattern):
    return fnmatch.fnmatchcase(path, pattern) or (pattern.endswith("/**") and path == pattern[:-3])


def validate():
    expected = controller_inventory()
    routes = gateway_inventory()
    errors = []
    for service in OPS:
        spec_path = OUT / f"{service}.openapi.json"
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        actual = set()
        for path, item in spec.get("paths", {}).items():
            for method, op in item.items():
                if method not in {"get", "post", "put", "patch", "delete"}: continue
                actual.add((method, path))
                for key in ("operationId", "summary", "description", "tags", "responses", "security"):
                    if key not in op or (key != "security" and not op.get(key)):
                        errors.append(f"{service} {method.upper()} {path}: missing {key}")
                if not op.get("responses"): errors.append(f"{service} {method.upper()} {path}: no responses")
                if "requestBody" in op and not op["requestBody"].get("content"):
                    errors.append(f"{service} {method.upper()} {path}: request body has no content schema")
                pathvars = set(re.findall(r"\{([^}]+)\}", path))
                params = {p.get("name") for p in op.get("parameters", []) if p.get("in") == "path" and p.get("required")}
                if pathvars != params: errors.append(f"{service} {method.upper()} {path}: path parameter mismatch {pathvars ^ params}")
                if "PUBLIC" in op.get("description", "") and op.get("security") != []:
                    errors.append(f"{service} {method.upper()} {path}: public operation must use empty security")
        if actual != expected[service]:
            errors.append(f"{service}: controller/spec mapping difference; missing={sorted(expected[service]-actual)} extra={sorted(actual-expected[service])}")
        for method, path in expected[service]:
            if not any(gateway_matches(path, pattern) for pattern in routes.get(service, [])):
                errors.append(f"{service}: {method.upper()} {path} has no configured gateway route for its owning service")
        if spec.get("openapi") != "3.1.0": errors.append(f"{service}: expected OpenAPI 3.1.0")
        for path, item in spec.get("paths", {}).items():
            for method, op in item.items():
                for response in op.get("responses", {}).values():
                    for media in response.get("content", {}).values():
                        ref = media.get("schema", {}).get("$ref", "")
                        if ref.startswith("#/components/schemas/") and ref.rsplit("/",1)[1] not in spec["components"]["schemas"]:
                            errors.append(f"{service} {method.upper()} {path}: unresolved schema {ref}")
                for ref in re.findall(r'"\$ref"\s*:\s*"([^\"]+)"', json.dumps(op)):
                    if ref.startswith("#/components/schemas/") and ref.rsplit("/",1)[1] not in spec["components"]["schemas"]:
                        errors.append(f"{service} {method.upper()} {path}: unresolved schema {ref}")
    if errors:
        raise SystemExit("OpenAPI validation failed:\n- " + "\n- ".join(errors))
    print("OpenAPI validity and controller mapping completeness: PASS (5 specs)")


def generate():
    OUT.mkdir(parents=True, exist_ok=True)
    for service in OPS:
        (OUT / f"{service}.openapi.json").write_text(json.dumps(build(service), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Generated {len(OPS)} service OpenAPI artifacts in {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    import sys
    if sys.argv[1:] == ["--generate"]: generate()
    elif sys.argv[1:] == ["--check"]: validate()
    else: raise SystemExit("Usage: python3 docs/api/build_openapi.py --generate|--check")
