import { interval, Subject } from 'rxjs';
import { JsonrpcResponseError } from 'src/app/shared/jsonrpc/base';
import { QueryHistoricTimeseriesDataRequest } from 'src/app/shared/jsonrpc/request/queryHistoricTimeseriesDataRequest';
import { QueryHistoricTimeseriesDataResponse } from 'src/app/shared/jsonrpc/response/queryHistoricTimeseriesDataResponse';
import { Service, ChannelAddress, Edge, EdgeConfig } from 'src/app/shared/shared';
import { takeUntil } from 'rxjs/operators';

export abstract class AbstractHistoryWidget {

    //observable is used to fetch new widget data every 5 minutes
    private refreshWidgetData = interval(300000);

    private ngUnsubscribe: Subject<void> = new Subject<void>();

    constructor(
        protected service: Service,
    ) { }

    /**
     * Subscribes to 5 minute Interval Observable to update data in Flat Widget
     */
    protected subscribeWidgetRefresh() {
        this.refreshWidgetData.pipe(takeUntil(this.ngUnsubscribe)).subscribe(() => {
            this.updateValues()
        })
    }

    /**
     * Unsubscribes to 5 minute Interval Observable
     */
    protected unsubscribeWidgetRefresh() {
        this.ngUnsubscribe.next();
        this.ngUnsubscribe.complete();
    }

    /**
     * Sends the Historic Timeseries Data Query and makes sure the result is not empty.
     * 
     * @param fromDate the From-Date
     * @param toDate   the To-Date
     * @param edge     the current Edge
     * @param ws       the websocket
    */
    protected queryHistoricTimeseriesData(fromDate: Date, toDate: Date): Promise<QueryHistoricTimeseriesDataResponse> {
        return new Promise((resolve, reject) => {
            this.service.getCurrentEdge().then(edge => {
                this.service.getConfig().then(config => {
                    this.getChannelAddresses(edge, config).then(channelAddresses => {
                        let request = new QueryHistoricTimeseriesDataRequest(fromDate, toDate, channelAddresses);
                        edge.sendRequest(this.service.websocket, request).then(response => {
                            let result = (response as QueryHistoricTimeseriesDataResponse).result;
                            if (Object.keys(result.data).length != 0 && Object.keys(result.timestamps).length != 0) {
                                resolve(response as QueryHistoricTimeseriesDataResponse);
                            } else {
                                reject(new JsonrpcResponseError(response.id, { code: 0, message: "Result was empty" }));
                            }
                        }).catch(reason => reject(reason));
                    }).catch(reason => reject(reason));
                })
            });
        });
    }

    /**
     * Gets the ChannelAdresses that should be queried.
     * 
     * @param edge the current Edge
     * @param config the EdgeConfig
     */
    protected abstract getChannelAddresses(edge: Edge, config: EdgeConfig): Promise<ChannelAddress[]>;

    /**
     * Updates and Fills the Chart
     */
    protected abstract updateValues()
}