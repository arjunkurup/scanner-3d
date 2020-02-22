#!/bin/bash

rm -Rf static/sfm-data/sfm-data-work
mkdir -p static/sfm-data/sfm-data-work/images
cp config.yaml static/sfm-data/sfm-data-work/
mv image_uploads/*.jpg static/sfm-data/sfm-data-work/images
bin/opensfm_run_all static/sfm-data/sfm-data-work
bin/opensfm export_ply static/sfm-data/sfm-data-work

