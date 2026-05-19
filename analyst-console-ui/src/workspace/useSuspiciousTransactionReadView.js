import { useCallback, useEffect, useRef, useState } from "react";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_SLICE = {
  content: [],
  size: 20,
  hasNext: false,
  nextCursor: null
};

export function useSuspiciousTransactionReadView({
  enabled = true,
  session,
  authProvider,
  apiClient,
  selectedSuspiciousTransactionId = null
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [slice, setSlice] = useState(INITIAL_SLICE);
  const [items, setItems] = useState([]);
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [listError, setListError] = useState(null);
  const [detail, setDetail] = useState(null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);
  const [detailError, setDetailError] = useState(null);
  const listSeqRef = useRef(0);
  const detailSeqRef = useRef(0);
  const listAbortRef = useRef(null);
  const detailAbortRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const loadList = useCallback(async ({ cursor = null, append = false } = {}) => {
    if (!effectiveApiClient) {
      return null;
    }
    listAbortRef.current?.abort();
    const abortController = new AbortController();
    listAbortRef.current = abortController;
    const requestSeq = listSeqRef.current + 1;
    listSeqRef.current = requestSeq;
    setIsLoadingList(true);
    setListError(null);
    try {
      const nextSlice = await effectiveApiClient.listSuspiciousTransactions({
        size: 20,
        cursor
      }, { signal: abortController.signal });
      if (listSeqRef.current !== requestSeq) {
        return null;
      }
      const normalizedSlice = normalizeSlice(nextSlice);
      setSlice(normalizedSlice);
      setItems((current) => append ? [...current, ...normalizedSlice.content] : normalizedSlice.content);
      return normalizedSlice;
    } catch (apiError) {
      if (listSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setListError(apiError);
      return null;
    } finally {
      if (listSeqRef.current === requestSeq) {
        setIsLoadingList(false);
        if (listAbortRef.current === abortController) {
          listAbortRef.current = null;
        }
      }
    }
  }, [effectiveApiClient]);

  const loadDetail = useCallback(async (suspiciousTransactionId) => {
    if (!effectiveApiClient || !suspiciousTransactionId) {
      return null;
    }
    detailAbortRef.current?.abort();
    const abortController = new AbortController();
    detailAbortRef.current = abortController;
    const requestSeq = detailSeqRef.current + 1;
    detailSeqRef.current = requestSeq;
    setIsLoadingDetail(true);
    setDetailError(null);
    try {
      const nextDetail = await effectiveApiClient.getSuspiciousTransaction(
        suspiciousTransactionId,
        { signal: abortController.signal }
      );
      if (detailSeqRef.current !== requestSeq) {
        return null;
      }
      setDetail(nextDetail);
      return nextDetail;
    } catch (apiError) {
      if (detailSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setDetailError(apiError);
      return null;
    } finally {
      if (detailSeqRef.current === requestSeq) {
        setIsLoadingDetail(false);
        if (detailAbortRef.current === abortController) {
          detailAbortRef.current = null;
        }
      }
    }
  }, [effectiveApiClient]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      clearList();
      clearDetail();
      return;
    }
    loadList({ cursor: null, append: false });
  }, [enabled, effectiveApiClient, loadList, sessionIdentity]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient || !selectedSuspiciousTransactionId) {
      clearDetail();
      return;
    }
    loadDetail(selectedSuspiciousTransactionId);
  }, [enabled, effectiveApiClient, loadDetail, selectedSuspiciousTransactionId, sessionIdentity]);

  useEffect(() => () => {
    listAbortRef.current?.abort();
    detailAbortRef.current?.abort();
    listSeqRef.current += 1;
    detailSeqRef.current += 1;
  }, []);

  function clearList() {
    listAbortRef.current?.abort();
    listAbortRef.current = null;
    listSeqRef.current += 1;
    setSlice(INITIAL_SLICE);
    setItems([]);
    setListError(null);
    setIsLoadingList(false);
  }

  function clearDetail() {
    detailAbortRef.current?.abort();
    detailAbortRef.current = null;
    detailSeqRef.current += 1;
    setDetail(null);
    setDetailError(null);
    setIsLoadingDetail(false);
  }

  return {
    slice,
    items,
    isLoadingList,
    listError,
    detail,
    isLoadingDetail,
    detailError,
    refreshList: () => loadList({ cursor: null, append: false }),
    loadNext: () => slice.hasNext && slice.nextCursor
      ? loadList({ cursor: slice.nextCursor, append: true })
      : Promise.resolve(null),
    refreshDetail: () => selectedSuspiciousTransactionId
      ? loadDetail(selectedSuspiciousTransactionId)
      : Promise.resolve(null)
  };
}

function normalizeSlice(slice) {
  return {
    content: Array.isArray(slice?.content) ? slice.content : [],
    size: Number(slice?.size) || 20,
    hasNext: Boolean(slice?.hasNext),
    nextCursor: slice?.hasNext ? slice?.nextCursor || null : null
  };
}

function isAbortError(error) {
  return error?.name === "AbortError";
}
