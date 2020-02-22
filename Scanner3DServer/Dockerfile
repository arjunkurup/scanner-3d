FROM paulinus/opensfm-docker-base:python3

COPY . /source/OpenSfM

WORKDIR /source/OpenSfM

RUN pip3 install -r requirements.txt && \
    python3 setup.py build

RUN mkdir -p /source/OpenSfM/image_uploads

