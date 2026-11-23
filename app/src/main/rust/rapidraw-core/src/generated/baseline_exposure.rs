#[derive(Clone, Copy, Debug, Default)]
pub struct RawExposure {
    pub scene_referred: bool,
    pub dng_baseline: Option<f32>,
    pub highlight_preservation: f32,
}

impl RawExposure {
    pub fn compensation(&self, options: BaselineOptions) -> f32 {
        let baseline = options
            .baseline_exposure
            .map_or(0.0, |amount| match self.dng_baseline {
                Some(dng_baseline) => dng_baseline,
                None if self.scene_referred => amount,
                None => 0.0,
            });
        let highlight_preservation = if options.compensate_highlight_preservation {
            self.highlight_preservation
        } else {
            0.0
        };
        baseline + highlight_preservation
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct BaselineOptions {
    pub baseline_exposure: Option<f32>,
    pub compensate_highlight_preservation: bool,
}

