package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
	"time"
)

type ProjectionJobModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	OperationId string `bson:"operation_id,omitempty" json:"operation_id,omitempty" form:"operation_id,omitempty"`

	EvidenceId string `bson:"evidence_id,omitempty" json:"evidence_id,omitempty" form:"evidence_id,omitempty"`

	ContentHash string `bson:"content_hash,omitempty" json:"content_hash,omitempty" form:"content_hash,omitempty"`

	Destination string `bson:"destination,omitempty" json:"destination,omitempty" form:"destination,omitempty"`

	State string `bson:"state,omitempty" json:"state,omitempty" form:"state,omitempty"`

	AttemptCount string `bson:"attempt_count,omitempty" json:"attempt_count,omitempty" form:"attempt_count,omitempty"`

	NextAttemptAt *time.Time `bson:"next_attempt_at,omitempty" json:"next_attempt_at,omitempty" form:"next_attempt_at,omitempty"`
}

func (p *ProjectionJobModel) CollectionName() string {
	return "branch_pos__projection_job"
}
