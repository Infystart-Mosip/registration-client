import { DatePipe } from '@angular/common';
import * as appConstants from './app.constants';

export default class Utils {
  static getCurrentDate() {
    const now = new Date();
    const pipe = new DatePipe('en-US');
    let formattedDate = pipe.transform(now, 'yyyy-MM-ddTHH:mm:ss.SSS');
    formattedDate = formattedDate + 'Z';
    return formattedDate;
  }

  static getURL(currentURL: string, nextRoute: string, numberofPop = 1) {
    const urlSegments = currentURL.split('/');
    for (let index = 0; index < numberofPop; index++) {
      urlSegments.pop();
    }
    urlSegments.push(nextRoute);
    const url = urlSegments.join('/');
    console.log(url);

    return url;
  }

  static getBookingDateTime(appointment_date: string, time_slot_from: string) {
    const date = appointment_date.split('-');
              let appointmentDateTime = date[2] + ' ' + appConstants.MONTHS[Number(date[1])] + ', ' + date[0];
              const time = time_slot_from.split(':');
              appointmentDateTime +=
                ', ' +
                (Number(time[0]) > 12 ? Number(time[0]) - 12 : Number(time[0])) +
                ':' +
                time[1] +
                (Number(time[0]) > 12 ? ' PM' : ' AM');
                return appointmentDateTime;
  }
}
