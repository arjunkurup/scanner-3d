import os
import urllib.request
from flask import Flask, flash, request, redirect, render_template
from werkzeug.utils import secure_filename
import shutil
from subprocess import call
from threading import Lock

lock = Lock()
ALLOWED_EXTENSIONS = set(['jpg', 'jpeg'])

UPLOAD_FOLDER = '/source/OpenSfM/image_uploads'

def allowed_file(filename):
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS


app = Flask(__name__)

@app.route('/scan', methods=['POST'])
def run_sfm():
    lock.acquire()
    if len(os.listdir(UPLOAD_FOLDER) ) > 0:
        try:
            flash('Starting point cloud generation...')
            rc = call('./generate_point_cloud.sh')
            flash('End point cloud generation....')
        except:
            flash('Got an exception')
    lock.release()
    return ('', 200)



@app.route('/upload', methods=['POST'])
def upload_file():
    if request.method == 'POST':
        #import pdb; pdb.set_trace()
        # check if the post request has the file part
        if 'file' not in request.files:
            flash('No file part')
            return redirect(request.url)
        file = request.files['file']
        if file.filename == '':
            flash('No file selected for uploading')
            return redirect(request.url)
        if file and allowed_file(file.filename):
            filename = secure_filename(file.filename)
            file.save(os.path.join(app.config['UPLOAD_FOLDER'], filename))
            flash('File successfully uploaded')
            #return redirect('/')
            return ('', 200)
        else:
            flash('Allowed file types are jpg, jpeg')
            return redirect(request.url)

if __name__ == "__main__":
    app.secret_key = "secret key"
    app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
    app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 2024
    app.run(host='0.0.0.0')

