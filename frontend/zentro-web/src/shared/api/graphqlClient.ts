import { apiClient, friendlyApiError } from './apiClient';

interface GraphqlError { message?: string; extensions?: { classification?: string } }
interface GraphqlResponse<T> { data?: T | null; errors?: GraphqlError[] }

export class GraphqlRequestError extends Error {
  constructor(message: string, readonly classification?: string) { super(message); }
}

/** Uses the existing gateway Axios instance and its JWT interceptor; partial failures never become mock data. */
export async function graphqlQuery<T>(query: string, variables: Record<string, unknown> = {}, signal?: AbortSignal): Promise<T> {
  try {
    const { data: response } = await apiClient.post<GraphqlResponse<T>>('/graphql', { query, variables }, { signal });
    if (response.errors?.length) {
      const error = response.errors[0];
      const kind = error.extensions?.classification;
      const message = kind === 'UNAUTHORIZED' ? 'Please sign in to load your dashboard.'
        : kind === 'FORBIDDEN' ? 'You do not have permission to view these records.'
        : kind === 'NOT_FOUND' ? 'Parcel not found.'
        : kind === 'BAD_REQUEST' ? (error.message || 'Please check the supplied values.')
        : 'Unable to load dashboard data. Please retry.';
      throw new GraphqlRequestError(message, kind);
    }
    if (response.data == null) throw new GraphqlRequestError('The server returned no dashboard data.');
    return response.data;
  } catch (error) {
    if (error instanceof GraphqlRequestError) throw error;
    throw new Error(friendlyApiError(error, 'Unable to load dashboard data. Please retry.'));
  }
}
