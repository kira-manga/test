package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
	"time"
)

type QuarantineEnvelopeModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	OperationId string `bson:"operation_id,omitempty" json:"operation_id,omitempty" form:"operation_id,omitempty"`

	ContentHash string `bson:"content_hash,omitempty" json:"content_hash,omitempty" form:"content_hash,omitempty"`

	CanonicalPayload string `bson:"canonical_payload,omitempty" json:"canonical_payload,omitempty" form:"canonical_payload,omitempty"`

	ReasonCode string `bson:"reason_code,omitempty" json:"reason_code,omitempty" form:"reason_code,omitempty"`

	ReceivedAt *time.Time `bson:"received_at,omitempty" json:"received_at,omitempty" form:"received_at,omitempty"`
}

func (p *QuarantineEnvelopeModel) CollectionName() string {
	return "branch_pos__quarantine_envelope"
}
