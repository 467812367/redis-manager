import axios from 'axios'
import message from '@/utils/message.js'
import { store } from '@/vuex/store.js'

/**
 * use RCTAPI or API
 * eg: getAnalyseResults
 * async getAnalyseResults (groupId) {
 *   const res = await getAnalyzeResults()
 *   //处理数据
 *   console.log(res.data)
 * }
 * @param {*} url url
 * @param {*} method GET | POST | DELETE | PUT, default GET
 * @param {*} params {}
 */
function RCTAPI (url, method = 'GET', params = {}) {
  return new Promise((resolve, reject) => {
    service({
      method: method,
      url: url,
      headers: {'UserIp': store.getters.getUserIp},
      data: method === 'POST' || method === 'PUT' ? params : null,
      params: method === 'GET' || method === 'DELETE' ? params : null
      // baseURL: root,
      // withCredentials: true //开启cookies
    }).then(res => {
      resolve(res)
    }).catch(err => {
      // reject(err)
      message.error(err.message)
    })
  })
}

/**
 * eg: deletAnalyze
 * async deletAnalyze (groupId) {
 *   const res = await deletAnalyze(1).then(res => {
 *   //处理数据
 *   console.log(res.data.data)
 *   })
 * }
 * @param {*} url url
 * @param {*} method GET | POST | DELETE | PUT, default GET
 * @param {*} params {}
 */
function API (url, method = 'GET', params = {}) {
  return service({
    method: method,
    url: url,
    headers: {'UserIp': store.getters.getUserIp},
    data: method === 'POST' || method === 'PUT' ? params : null,
    params: method === 'GET' || method === 'DELETE' ? params : null
  })
}

if (process.env.NODE_ENV === 'development') {
  axios.defaults.baseURL = '/apis'
} else if (process.env.NODE_ENV === 'production') {
  axios.defaults.baseURL = '/'
}

const service = axios.create({
  timeout: 10000
})

service.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 0) {
      message.error(res.message || 'error')
      return Promise.reject(new Error(res.message || 'error'))
    } else {
      return Promise.resolve(res)
    }
  },
  error => {
    // if (error.response.code === 401) {
    //   message.warning('Session timeout')
    //   router.push({ name: 'login' })
    // } else {
    //   return Promise.reject(error)
    // }
    return Promise.reject(error)
  }
)

export const cronExpress = (cron) => API(`/rdb/test_corn`, 'POST', cron)

export const analyzeJob = (data) => API(`/rdb/allocation_job`, 'POST', data)

export const getClusterNodes = (id) => API(`/node-manage/getAllNodeList/${id}`)

export const getCluster = (id) => API(`/cluster/getClusterList/${id}`)

export const getAnalysisList = () => API(`/rdb`)

export const addAnalysisList = (data) => API(`/rdb`, 'POST', data)

export const updateAnalyzeList = (data) => API(`/rdb`, 'PUT', data)

export const deletAnalyze = (id) => API(`/rdb?id=${id}`, 'DELETE')

export const getAnalyzeResults = () => RCTAPI('/rdb/results')

export const getPieByType = (clusterId, scheduleId) => RCTAPI('/rdb/chart/DataTypeAnalyze', 'GET', {clusterId, scheduleId})

export const getPrefixKeysCount = (clusterId, scheduleId) => RCTAPI('/rdb/line/prefix/PrefixKeyByCount', 'GET', {clusterId, scheduleId})

export const getPrefixKeysMemory = (clusterId, scheduleId) => RCTAPI('/rdb/line/prefix/PrefixKeyByMemory', 'GET', {clusterId, scheduleId})

export const getTop1000KeysByPrefix = (clusterId, scheduleId) => RCTAPI('/rdb/table/prefix', 'GET', {clusterId, scheduleId})

export const getKeysTTLInfo = (clusterId, scheduleId) => RCTAPI('/rdb/chart/TTLAnalyze', 'GET', {clusterId, scheduleId})

export const getTop1000KeysByString = (clusterId, scheduleId) => RCTAPI('/rdb/top_key', 'GET', { clusterId: clusterId, scheduleId: scheduleId, type: 0 })

export const getTop1000KeysByHash = (clusterId, scheduleId) => RCTAPI('/rdb/top_key', 'GET', { clusterId: clusterId, scheduleId: scheduleId, type: 5 })

export const getTop1000KeysByList = (clusterId, scheduleId) => RCTAPI('/rdb/top_key', 'GET', { clusterId: clusterId, scheduleId: scheduleId, type: 10 })

export const getTop1000KeysBySet = (clusterId, scheduleId) => RCTAPI('/rdb/top_key', 'GET', { clusterId: clusterId, scheduleId: scheduleId, type: 15 })

export const getTimeData = (clusterId, scheduleId) => RCTAPI('/rdb/all/schedule_id', 'GET', { clusterId, scheduleId })

export const getScheduleDetail = (id) => API(`/rdb/schedule_detail/${id}`)

export const cancelAnalyzeTask = (id, scheduleID) => API(`/rdb/cance_job/${id}/${scheduleID}`)
