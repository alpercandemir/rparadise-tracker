import axios from 'axios'

const API_BASE = '/api'

export const radioParadiseAPI = {
    async getDashboard() {
        const response = await axios.get(`${API_BASE}/songs/dashboard`)
        return response.data
    },

    async getSongDetails(id, signal) {
        const response = await axios.get(`${API_BASE}/songs/${id}`, { signal })
        return response.data
    },

    async getCurrentSongs() {
        const response = await axios.get(`${API_BASE}/songs/current`)
        return response.data
    },
    async getRecordsPage(tab, params, signal) {
        const endpoint = tab === 'plays' ? 'plays' : 'tracks'
        const response = await axios.get(`${API_BASE}/records/${endpoint}`, { params, signal })
        return response.data
    },

    // Get all songs
    async getAllSongs() {
        const response = await axios.get(`${API_BASE}/songs`)
        return response.data
    },

    // Get songs by channel
    async getSongsByChannel(channelId) {
        const response = await axios.get(`${API_BASE}/songs/channel/${channelId}`)
        return response.data
    },

    // Get recent songs
    async getRecentSongs() {
        const response = await axios.get(`${API_BASE}/songs/recent`)
        return response.data
    },

    // Get most played songs
    async getMostPlayedSongs() {
        const response = await axios.get(`${API_BASE}/songs/most-played`)
        return response.data
    }
}

export const CHANNELS = {
    '0': { name: 'Main Mix', color: '#5145a6' },
    '1': { name: 'Mellow Mix', color: '#176d82' },
    '2': { name: 'Rock Mix', color: '#9a3971' }
}
