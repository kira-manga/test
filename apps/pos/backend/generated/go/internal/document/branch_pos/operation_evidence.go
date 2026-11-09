package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
	"time"
)

type OperationEvidenceModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	OperationId string `bson:"operation_id,omitempty" json:"operation_id,omitempty" form:"operation_id,omitempty"`

	AuthorityEpoch string `bson:"authority_epoch,omitempty" json:"authority_epoch,omitempty" form:"authority_epoch,omitempty"`

	JournalSequence string `bson:"journal_sequence,omitempty" json:"journal_sequence,omitempty" form:"journal_sequence,omitempty"`

	OriginalActorId string `bson:"original_actor_id,omitempty" json:"original_actor_id,omitempty" form:"original_actor_id,omitempty"`

	OriginMasterDeviceId string `bson:"origin_master_device_id,omitempty" json:"origin_master_device_id,omitempty" form:"origin_master_device_id,omitempty"`

	ContentHash string `bson:"content_hash,omitempty" json:"content_hash,omitempty" form:"content_hash,omitempty"`

	PayloadEncoding string `bson:"payload_encoding,omitempty" json:"payload_encoding,omitempty" form:"payload_encoding,omitempty"`

	CanonicalPayload string `bson:"canonical_payload,omitempty" json:"canonical_payload,omitempty" form:"canonical_payload,omitempty"`

	MasterCommittedAt *time.Time `bson:"master_committed_at,omitempty" json:"master_committed_at,omitempty" form:"master_committed_at,omitempty"`
}

func (p *OperationEvidenceModel) CollectionName() string {
	return "branch_pos__operation_evidence"
}
