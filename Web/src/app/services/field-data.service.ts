import { Injectable } from '@angular/core';
import { FieldVisitTemp } from 'app/models/field-visit.model';
import { AngularFirestore } from '@angular/fire/firestore';
import { FieldData } from 'app/models/field-data.model';
import { HttpClient } from '@angular/common/http';
import { ThrowStmt } from '@angular/compiler';

@Injectable({
  providedIn: 'root'
})
export class FieldDataService {

  // Override this deployment URL with the environment-specific prediction API before release.
  private readonly predictionApiUrl = 'https://docker-python3-opencv-uvbeppe6ea-el.a.run.app';

  constructor(private fireStore : AngularFirestore, private http : HttpClient) { }

  

  getFieldData(fieldData: FieldData) {
    
    return this.fireStore.collection('TestingFieldData', ref => ref.where('longitude', '==', fieldData.longitude).where('latitude', '==' , fieldData.latitude).where('requestId', '==', fieldData.requestId)).snapshotChanges();
  }

  insertFieldData(fieldData : FieldData){
    return new Promise<any>((resolve, reject) => {
      this.fireStore.collection('TestingFieldData').add(fieldData)
        .then(
          res => {
            resolve("Success");
          }
          , err => reject(err.message))
    })
  }

  getLevelFromServer(fd : FormData){
    return this.http.post<number>(this.predictionApiUrl + '/svcprocess', fd);
  }

  getFieldDataUsingRequestId(fieldData : FieldData){
    return this.fireStore.collection('TestingFieldData', ref => ref.where('requestId', '==', fieldData.requestId)).snapshotChanges();
  }

  getAllFieldData(){
    return this.fireStore.collection('FieldData').snapshotChanges();
  }
}
