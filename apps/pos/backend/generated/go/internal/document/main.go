package document

import (
	"go-viki/internal/document/branch_pos"
	"reflect"
)

var Document = map[string]reflect.Type{
	"branch_pos__branch_binding":       reflect.TypeFor[branch_pos.BranchBindingModel](),
	"branch_pos__company_profile":      reflect.TypeFor[branch_pos.CompanyProfileModel](),
	"branch_pos__ingestion_checkpoint": reflect.TypeFor[branch_pos.IngestionCheckpointModel](),
	"branch_pos__ingestion_receipt":    reflect.TypeFor[branch_pos.IngestionReceiptModel](),
	"branch_pos__operation_evidence":   reflect.TypeFor[branch_pos.OperationEvidenceModel](),
	"branch_pos__projection_job":       reflect.TypeFor[branch_pos.ProjectionJobModel](),
	"branch_pos__quarantine_envelope":  reflect.TypeFor[branch_pos.QuarantineEnvelopeModel](),
}
