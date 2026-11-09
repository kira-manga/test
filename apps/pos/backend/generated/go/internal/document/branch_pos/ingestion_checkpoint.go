package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
)

type IngestionCheckpointModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	AuthorityEpoch string `bson:"authority_epoch,omitempty" json:"authority_epoch,omitempty" form:"authority_epoch,omitempty"`

	ContiguousAcceptedSequence string `bson:"contiguous_accepted_sequence,omitempty" json:"contiguous_accepted_sequence,omitempty" form:"contiguous_accepted_sequence,omitempty"`

	Stream string `bson:"stream,omitempty" json:"stream,omitempty" form:"stream,omitempty"`
}

func (p *IngestionCheckpointModel) CollectionName() string {
	return "branch_pos__ingestion_checkpoint"
}
