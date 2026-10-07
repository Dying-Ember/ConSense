import type { InspectionRecord } from './inspection'

/** Saved metadata only. These records never promote a source or confirm text quality. */
export interface InspectionOcrStageLine extends InspectionRecord {
  lineId: string
  detectionOrdinalZeroBased?: number
  detectorConfidence?: number | null
  detectorPreprocessedPolygon?: number[][]
  originalImagePolygonBeforeFiltering?: number[][]
  originalPageNormalizedPolygon?: number[][]
  classification?: InspectionRecord
  recognition?: InspectionRecord
  blankTextFilter?: InspectionRecord
  scoreFilter?: InspectionRecord
  terminalStage?: string
  finalOrdinalZeroBased?: number | null
}

export interface InspectionOcrLineDiagnostics extends InspectionRecord {
  schema: 'rapidocr-line-flow-observation-v1'
  pages: Array<InspectionRecord & { lines: InspectionOcrStageLine[]; complete?: boolean; mappingVerified?: boolean }>
}

export interface InspectionRasterCell extends InspectionRecord {
  id: string
  row: number
  column: number
  rowSpan: number
  columnSpan: number
  bboxXYXY: number[]
  text: string
  sourceLineIds: string[]
  sourceLineAssignments: InspectionRecord[]
  textMayCrossRasterCellBoundaries: boolean
  textStatus: string
  headerStatus: string
  nativeCell: boolean
}

export interface InspectionRasterTable extends InspectionRecord {
  id: string
  rowBoundaries: number[]
  columnBoundaries: number[]
  cells: InspectionRasterCell[]
  nativeTable: boolean
  semanticStructureVerified: boolean
}

/** The frozen producer envelope, including the exact input raster's coordinate frame. */
export interface InspectionRasterTableCandidates extends InspectionRecord {
  coordinateFrame: string
  originalPageTransform?: InspectionRecord
  result: InspectionRecord & { tables: InspectionRasterTable[] }
}

export interface InspectionOcrExperiment extends InspectionRecord {
  id: string
  lineStageDiagnostics?: InspectionOcrLineDiagnostics
  rasterTableCandidates?: InspectionRasterTableCandidates
}
